/*
 * Copyright 2020 Siphalor and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 * See the License for the specific language governing
 * permissions and limitations under the License.
 */

package de.siphalor.mousewheelie.client.inventory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import de.siphalor.mousewheelie.MWConfig;
import de.siphalor.mousewheelie.MouseWheelie;
import de.siphalor.mousewheelie.client.MWClient;
import de.siphalor.mousewheelie.client.network.ClickEventFactory;
import de.siphalor.mousewheelie.client.network.InteractionManager;
import de.siphalor.mousewheelie.client.util.ItemStackUtils;
import de.siphalor.mousewheelie.client.util.ReverseIterator;
import de.siphalor.mousewheelie.client.util.inject.ISlot;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Inventory;
//- import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

@Environment(EnvType.CLIENT)
@SuppressWarnings("WeakerAccess")
public class ContainerScreenHelper<T extends AbstractContainerScreen<?>> {
	protected final T screen;
	protected final boolean hasOtherInventory;
	protected final ClickEventFactory clickEventFactory;
	protected final ReadWriteLock slotStatesLock = new ReentrantReadWriteLock();
	protected final Int2ObjectMap<SlotInteractionState> slotStates;

	public static final int INVALID_SCOPE = Integer.MAX_VALUE;

	protected ContainerScreenHelper(T screen, ClickEventFactory clickEventFactory) {
		this.screen = screen;
		this.hasOtherInventory = determineHasOtherInventory();
		this.clickEventFactory = clickEventFactory;
		this.slotStates = new Int2ObjectArrayMap<>(10);
	}

	@SuppressWarnings("unchecked")
	public static <T extends AbstractContainerScreen<?>> ContainerScreenHelper<T> of(T screen, ClickEventFactory clickEventFactory) {
		if (screen instanceof CreativeModeInventoryScreen) {
			return (ContainerScreenHelper<T>) new CreativeInventoryContainerScreenHelper<>((CreativeModeInventoryScreen) screen, clickEventFactory);
		} else if (screen instanceof InventoryScreen) {
			return new PlayerInventoryFocusedContainerScreenHelper<>(screen, clickEventFactory);
		}
		return new ContainerScreenHelper<>(screen, clickEventFactory);
	}

	private boolean determineHasOtherInventory() {
		for (Slot slot : screen.getMenu().slots) {
			if (!(slot.container instanceof Inventory)) {
				return true;
			}
		}
		return false;
	}

	public InteractionManager.InteractionEvent createClickEvent(
			Slot slot,
			int action,
			//# if MC_VERSION_NUMBER >= 260100
			ContainerInput actionType
			//# else
			//- ClickType actionType
			//# end
	) {
		if (getSlotState(slot).areInteractionsLocked()) {
			return null;
		}
		return clickEventFactory.create(slot, action, actionType);
	}

	public SlotInteractionState getSlotState(Slot slot) {
		Lock readLock = slotStatesLock.readLock();
		readLock.lock();
		try {
			SlotInteractionState state = slotStates.get(((ISlot) slot).mouseWheelie_getIdInContainer());
			if (state == null) {
				return SlotInteractionState.NORMAL;
			}
			return state;
		} finally {
			readLock.unlock();
		}
	}

	public void setSlotState(Slot slot, SlotInteractionState state) {
		Lock writeLock = slotStatesLock.writeLock();
		writeLock.lock();
		try {
			int slotId = ((ISlot) slot).mouseWheelie_getIdInContainer();
			if (state == SlotInteractionState.NORMAL) {
				slotStates.remove(slotId);
			} else {
				slotStates.put(slotId, state);
			}
		} finally {
			writeLock.unlock();
		}
	}

	public void unlockSlot(Slot slot) {
		setSlotState(slot, SlotInteractionState.NORMAL);
	}

	private InteractionManager.InteractionEvent lockBefore(InteractionManager.InteractionEvent event, Slot slot, SlotInteractionState slotState) {
		if (event == null) {
			return null;
		}

		return new InteractionManager.CallbackEvent(() -> {
			setSlotState(slot, slotState);
			return event.send();
		}, event.shouldRunOnMainThread());
	}

	private InteractionManager.InteractionEvent unlockAfter(InteractionManager.InteractionEvent event, Slot slot) {
		if (event == null) {
			return null;
		}

		return new InteractionManager.CallbackEvent(() -> {
			InteractionManager.Waiter waiter = event.send();
			unlockSlot(slot);
			return waiter;
		}, event.shouldRunOnMainThread());
	}

	public void scroll(Slot referenceSlot, boolean scrollUp) {
		if (MouseWheelie.config.general.enableDropModifier && MWClient.DROP_MODIFIER.isDown()) {
			scrollDrop(referenceSlot);
			return;
		}

		// Shall send determines whether items from the referenceSlot shall be moved to another scope. Otherwise the referenceSlot will receive items.
		boolean shallSend;
		if (MouseWheelie.config.scrolling.directionalScrolling) {
			shallSend = shallSendFromSlot(referenceSlot, scrollUp);
		} else {
			// scroll by amount, up => more (receive); down => less (send)
			shallSend = !scrollUp;
			scrollUp = false;
		}

		if (shallSend) {
			scrollSend(referenceSlot);
		} else {
			scrollReceive(referenceSlot, scrollUp);
		}
	}

	public boolean shallSendFromSlot(Slot slot, boolean scrollUp) {
		return isInLowerRegion(getScope(slot)) == scrollUp;
	}

	private void scrollSend(Slot referenceSlot) {
		// If deposit modifier and restock modifier are equal, deposit modifier takes precedence
		if (MWClient.DEPOSIT_MODIFIER.isDown()) {
			depositAllFrom(referenceSlot);
			return;
		}
		if (MWClient.RESTOCK_MODIFIER.isDown()) {
			restockAll(getComplementaryScope(getScope(referenceSlot)));
			return;
		}

		if (!referenceSlot.mayPlace(ItemStack.EMPTY)) {
			sendStack(referenceSlot);
		}
		if (MWClient.ALL_OF_KIND_MODIFIER.isDown()) {
			sendAllOfAKind(referenceSlot);
		} else if (MWClient.WHOLE_STACK_MODIFIER.isDown()) {
			sendStack(referenceSlot);
		} else {
			sendSingleItem(referenceSlot);
		}
	}

	private void scrollReceive(Slot referenceSlot, boolean scrollUp) {
		// If deposit modifier and restock modifier are equal, restock modifier takes precedence
		if (MWClient.RESTOCK_MODIFIER.isDown()) {
			if (MWClient.WHOLE_STACK_MODIFIER.isDown()) {
				restockAll(referenceSlot);
			} else {
				restockAllOfAKind(referenceSlot);
			}
			return;
		}
		if (MWClient.DEPOSIT_MODIFIER.isDown()) {
			depositAllFrom(getComplementaryScope(getScope(referenceSlot)));
			return;
		}

		ItemStack referenceStack = referenceSlot.getItem().copy();
		int referenceScope = getScope(referenceSlot);
		boolean wholeStackModifier = MWClient.WHOLE_STACK_MODIFIER.isDown();
		boolean allOfKindModifier = MWClient.ALL_OF_KIND_MODIFIER.isDown();
		if (wholeStackModifier || allOfKindModifier) {
			for (Slot slot : screen.getMenu().slots) {
				if (getScope(slot) == referenceScope) continue;
				if (ItemStackUtils.areItemsOfSameKind(slot.getItem(), referenceStack)) {
					sendStack(slot);
					if (!allOfKindModifier) {
						break;
					}
				}
			}
		} else {
			Slot moveSlot = null;
			int stackSize = Integer.MAX_VALUE;
			for (Slot slot : screen.getMenu().slots) {
				int slotScope = getScope(slot);
				if (slotScope == referenceScope) continue;
				if (isInLowerRegion(slotScope) == scrollUp) {
					if (ItemStackUtils.areItemsOfSameKind(slot.getItem(), referenceStack)) {
						if (slot.getItem().getCount() < stackSize) {
							stackSize = slot.getItem().getCount();
							moveSlot = slot;
							if (stackSize == 1) {
								break;
							}
						}
					}
				}
			}
			if (moveSlot != null) {
				sendSingleItem(moveSlot);
			}
		}
	}

	private boolean isInLowerRegion(int scope) {
		return scope <= 0;
	}

	public boolean isHotbarSlot(Slot slot) {
		return ((ISlot) slot).mouseWheelie_getIndexInInv() < 9;
	}

	public int getScope(Slot slot) {
		return getScope(slot, false);
	}

	public int getScope(Slot slot, boolean preferSmallerScopes) {
		if (slot.container == null || ((ISlot) slot).mouseWheelie_getIndexInInv() >= slot.container.getContainerSize() || !slot.mayPlace(ItemStack.EMPTY)) {
			return INVALID_SCOPE;
		}
		if (slot.container instanceof Inventory) {
			if (isHotbarSlot(slot)) {
				if (MouseWheelie.config.general.hotbarScoping == MWConfig.General.HotbarScoping.HARD
						|| MouseWheelie.config.general.hotbarScoping == MWConfig.General.HotbarScoping.SOFT && preferSmallerScopes) {
					return -1;
				}
			}
			return 0;
		}
		return 1;
	}

	public void runInScope(int scope, Consumer<Slot> slotConsumer) {
		runInScope(scope, false, slotConsumer);
	}

	public void runInScope(int scope, boolean preferSmallerScopes, Consumer<Slot> slotConsumer) {
		for (Slot slot : screen.getMenu().slots) {
			if (getScope(slot, preferSmallerScopes) == scope) {
				slotConsumer.accept(slot);
			}
		}
	}

	public int getComplementaryScope(int scope) {
		if (scope <= 0) {
			return 1;
		}
		return 0;
	}

	public void sendSingleItem(Slot slot) {
		SlotInteractionState slotState = getSlotState(slot);
		if (slotState.areInteractionsLocked()) {
			return;
		}

		if (slotState.isAmountStable() && slot.getItem().getCount() == 1) {
			InteractionManager.push(
					//# if MC_VERSION_NUMBER >= 260100
					clickEventFactory.create(slot, 0, ContainerInput.QUICK_MOVE)
					//# else
					//- clickEventFactory.create(slot, 0, ClickType.QUICK_MOVE)
					//# end
			);
			return;
		}
		//# if MC_VERSION_NUMBER >= 260100
		InteractionManager.push(lockBefore(
				clickEventFactory.create(slot, 0, ContainerInput.PICKUP),
				slot,
				SlotInteractionState.UNSTABLE_AMOUNT
		));
		InteractionManager.push(clickEventFactory.create(slot, 1, ContainerInput.PICKUP));
		InteractionManager.push(clickEventFactory.create(slot, 0, ContainerInput.QUICK_MOVE));
		InteractionManager.push(unlockAfter(clickEventFactory.create(slot, 0, ContainerInput.PICKUP), slot));
		//# else
		//- InteractionManager.push(lockBefore(
		//- 		clickEventFactory.create(slot, 0, ClickType.PICKUP),
		//- 		slot,
		//- 		SlotInteractionState.UNSTABLE_AMOUNT
		//- ));
		//- InteractionManager.push(clickEventFactory.create(slot, 1, ClickType.PICKUP));
		//- InteractionManager.push(clickEventFactory.create(slot, 0, ClickType.QUICK_MOVE));
		//- InteractionManager.push(unlockAfter(clickEventFactory.create(slot, 0, ClickType.PICKUP), slot));
		//# end
	}

	public void sendStack(Slot slot) {
		InteractionManager.push(
				//# if MC_VERSION_NUMBER >= 260100
				createClickEvent(slot, 0, ContainerInput.QUICK_MOVE)
				//# else
				//- createClickEvent(slot, 0, ClickType.QUICK_MOVE)
				//# end
		);
	}

	public void sendStackLocked(Slot slot) {
		if (getSlotState(slot).areInteractionsLocked()) {
			return;
		}

		setSlotState(slot, SlotInteractionState.TEMP_LOCKED);
		InteractionManager.push(unlockAfter(
				//# if MC_VERSION_NUMBER >= 260100
				clickEventFactory.create(slot, 0, ContainerInput.QUICK_MOVE),
				//# else
				//- clickEventFactory.create(slot, 0, ClickType.QUICK_MOVE),
				//# end
				slot
		));
	}

	public void sendAllOfAKind(Slot referenceSlot) {
		ItemStack stack = referenceSlot.getItem();
		if (stack.isEmpty()) {
			return;
		}

		ItemStack referenceStack = stack.copy();
		runInScope(getScope(referenceSlot), slot -> {
			if (ItemStackUtils.areItemsOfSameKind(slot.getItem(), referenceStack)) {
				sendStack(slot);
			}
		});
	}

	public void sendAllFrom(Slot referenceSlot) {
		runInScope(getScope(referenceSlot, true), true, this::sendStack);
	}

	public void depositAllFrom(Slot referenceSlot) {
		depositAllFrom(getScope(referenceSlot, false));
	}

	public void depositAllFrom(int scope) {
		int complementaryScope = getComplementaryScope(scope);

		Set<ItemKind> itemKinds = new HashSet<>();
		runInScope(complementaryScope, slot -> {
			if (slot.hasItem()) {
				itemKinds.add(ItemKind.of(slot.getItem()));
			}
		});

		runInScope(scope, slot -> {
			if (slot.hasItem() && itemKinds.contains(ItemKind.of(slot.getItem()))) {
				sendStackLocked(slot);

			}
		});
	}

	public void restockAllOfAKind(Slot referenceSlot) {
		ItemStack referenceStack = referenceSlot.getItem();
		if (referenceStack.isEmpty()) {
			return;
		}

		int scope = getScope(referenceSlot, true);
		int complementaryScope = getComplementaryScope(scope);
		restockAllOfAKind(
				screen.getMenu().slots.stream()
						.filter(slot -> getScope(slot, true) == scope && ItemStackUtils.areItemsOfSameKind(slot.getItem(), referenceStack))
						.iterator(),
				complementaryScope
		);
	}

	private void restockAllOfAKind(Iterator<Slot> targetSlots, int complementaryScope) {
		Iterator<Slot> takeSlots = ReverseIterator.of(screen.getMenu().slots);
		Slot currentTakeSlot = null;
		int currentTakeCount = 0;

		while (targetSlots.hasNext()) {
			Slot targetSlot = targetSlots.next();
			ItemStack targetStack = targetSlot.getItem();
			int space = targetStack.getMaxStackSize() - targetStack.getCount();

			while (space > 0) {
				if (currentTakeCount == 0) {
					while (true) {
						if (!takeSlots.hasNext()) {
							return;
						}

						currentTakeSlot = takeSlots.next();
						if (getScope(currentTakeSlot, false) != complementaryScope) {
							continue;
						}

						ItemStack currentTakeStack = currentTakeSlot.getItem();
						currentTakeCount = currentTakeStack.getCount();

						if (currentTakeCount <= 0) {
							continue;
						}
						if (ItemStackUtils.areItemsOfSameKind(currentTakeStack, targetStack)) {
							break;
						}
					}
					InteractionManager.push(
							//# if MC_VERSION_NUMBER >= 260100
							clickEventFactory.create(currentTakeSlot, 0, ContainerInput.PICKUP)
							//# else
							//- clickEventFactory.create(currentTakeSlot, 0, ClickType.PICKUP)
							//# end
					);
				}

				InteractionManager.push(
						//# if MC_VERSION_NUMBER >= 260100
						clickEventFactory.create(targetSlot, 0, ContainerInput.PICKUP)
						//# else
						//- clickEventFactory.create(targetSlot, 0, ClickType.PICKUP)
						//# end
				);
				space -= currentTakeCount;

				if (space <= 0) {
					currentTakeCount = -space;
					continue;
				}
				currentTakeCount = 0;
			}
		}

		if (currentTakeCount > 0) {
			InteractionManager.push(
					//# if MC_VERSION_NUMBER >= 260100
					clickEventFactory.create(currentTakeSlot, 0, ContainerInput.PICKUP)
					//# else
					//- clickEventFactory.create(currentTakeSlot, 0, ClickType.PICKUP)
					//# end
			);
		}
	}

	public void restockAll(Slot referenceSlot) {
		restockAll(getScope(referenceSlot, false));
	}

	public void restockAll(int scope) {
		ListMultimap<ItemKind, Slot> slotsByItemKind = ArrayListMultimap.create();
		runInScope(scope, slot -> {
			ItemStack stack = slot.getItem();
			int count = stack.getCount();
			if (count > 0 && count < stack.getMaxStackSize()) {
				slotsByItemKind.put(ItemKind.of(stack), slot);
			}
		});
		int complementaryScope = getComplementaryScope(scope);

		slotsByItemKind.asMap().forEach((itemKind, slots) ->
				restockAllOfAKind(slots.iterator(), complementaryScope)
		);
	}

	private void scrollDrop(Slot referenceSlot) {
		if (MWClient.ALL_OF_KIND_MODIFIER.isDown()) {
			if (MWClient.WHOLE_STACK_MODIFIER.isDown()) {
				dropAllFrom(referenceSlot);
			} else {
				dropAllOfAKind(referenceSlot);
			}
		} else if (MWClient.WHOLE_STACK_MODIFIER.isDown()) {
			dropStack(referenceSlot);
		} else {
			dropSingleItem(referenceSlot);
		}
	}

	public void dropSingleItem(Slot slot) {
		if (getSlotState(slot).areInteractionsLocked()) {
			return;
		}

		InteractionManager.push(
				//# if MC_VERSION_NUMBER >= 260100
				createClickEvent(slot, 0, ContainerInput.THROW)
				//# else
				//- createClickEvent(slot, 0, ClickType.THROW)
				//# end
		);
	}

	public void dropStack(Slot slot) {
		if (getSlotState(slot).areInteractionsLocked()) {
			return;
		}

		InteractionManager.push(
				//# if MC_VERSION_NUMBER >= 260100
				createClickEvent(slot, 1, ContainerInput.THROW)
				//# else
				//- createClickEvent(slot, 1, ClickType.THROW)
				//# end
		);
	}

	public void dropStackLocked(Slot slot) {
		if (getSlotState(slot).areInteractionsLocked()) {
			return;
		}

		setSlotState(slot, SlotInteractionState.TEMP_LOCKED);
		InteractionManager.push(unlockAfter(
				//# if MC_VERSION_NUMBER >= 260100
				clickEventFactory.create(slot, 1, ContainerInput.THROW),
				//# else
				//- clickEventFactory.create(slot, 1, ClickType.THROW),
				//# end
				slot
		));
	}

	public void dropAllOfAKind(Slot referenceSlot) {
		ItemStack stack = referenceSlot.getItem();
		if (stack.isEmpty()) {
			return;
		}

		ItemStack referenceStack = stack.copy();
		runInScope(getScope(referenceSlot), slot -> {
			if (ItemStackUtils.areItemsOfSameKind(slot.getItem(), referenceStack)) {
				dropStack(slot);
			}
		});
	}

	public void dropAllFrom(Slot referenceSlot) {
		runInScope(getScope(referenceSlot, true), true, this::dropStack);
	}
}
