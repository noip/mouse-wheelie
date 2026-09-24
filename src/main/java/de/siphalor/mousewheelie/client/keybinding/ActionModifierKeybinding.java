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

package de.siphalor.mousewheelie.client.keybinding;

import com.mojang.blaze3d.platform.InputConstants;
import de.siphalor.amecs.priority_key_mappings.api.AmecsPriorityKeyMapping;

public class ActionModifierKeybinding extends MWBaseKeyMapping implements AmecsPriorityKeyMapping {
	//# if MC_VERSION_NUMBER >= 12109
	public ActionModifierKeybinding(String name, InputConstants.Key key, Category category) {
	//# else
	//- public ActionModifierKeybinding(String name, InputConstants.Key key, String category) {
	//# end
		super(name, key, category);
	}

	@Override
	public boolean onPressedPriority() {
		setDown(true);
		return false;
	}

	@Override
	public boolean onReleasedPriority() {
		setDown(false);
		return false;
	}

	@Override
	public boolean isDown() {
		if (super.isDown()) {
			return true;
		}
		if (isUnbound()) {
			return false;
		}
		//# if MC_VERSION_NUMBER >= 260300
		if (key.getType() == InputConstants.Type.KEYBOARD) {
			int code = key.getValue();
			if (InputConstants.isKeyDown(code)) {
				return true;
			}
			if (code == InputConstants.KEY_LALT && InputConstants.isKeyDown(InputConstants.KEY_RALT)) {
				return true;
			}
			if (code == InputConstants.KEY_LSHIFT && InputConstants.isKeyDown(InputConstants.KEY_RSHIFT)) {
				return true;
			}
			if (code == InputConstants.KEY_LCONTROL && InputConstants.isKeyDown(InputConstants.KEY_RCONTROL)) {
				return true;
			}
		}
		//# elif MC_VERSION_NUMBER >= 12109
		//- if (key.getType() == InputConstants.Type.KEYSYM) {
		//- 	long window = net.minecraft.client.Minecraft.getInstance().getWindow().handle();
		//- 	int code = key.getValue();
		//- 	if (InputConstants.isKeyDown(window, code)) {
		//- 		return true;
		//- 	}
		//- 	if (code == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT && InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT)) {
		//- 		return true;
		//- 	}
		//- 	if (code == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT && InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT)) {
		//- 		return true;
		//- 	}
		//- 	if (code == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL && InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL)) {
		//- 		return true;
		//- 	}
		//- }
		//# else
		//- if (key.getType() == InputConstants.Type.KEYSYM) {
		//- 	long window = net.minecraft.client.Minecraft.getInstance().getWindow().getWindow();
		//- 	int code = key.getValue();
		//- 	if (InputConstants.isKeyDown(window, code)) {
		//- 		return true;
		//- 	}
		//- 	if (code == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT && InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT)) {
		//- 		return true;
		//- 	}
		//- 	if (code == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT && InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT)) {
		//- 		return true;
		//- 	}
		//- 	if (code == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL && InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL)) {
		//- 		return true;
		//- 	}
		//- }
		//# end
		return false;
	}
}
