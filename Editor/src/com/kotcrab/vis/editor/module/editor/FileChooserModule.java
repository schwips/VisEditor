/*
 * Copyright 2014-2015 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kotcrab.vis.editor.module.editor;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.kotcrab.vis.editor.Editor;
import com.kotcrab.vis.ui.widget.file.FileChooser;
import com.kotcrab.vis.ui.widget.file.FileChooser.Mode;
import com.kotcrab.vis.ui.widget.file.FileChooser.SelectionMode;
import com.kotcrab.vis.ui.widget.file.FileChooserAdapter;
import com.kotcrab.vis.ui.widget.file.FileChooserListener;

public class FileChooserModule extends EditorModule {
	private FileChooser chooser;

	private FileChooserListener listener;

	@Override
	public void init () {
		chooser = new FileChooser(Mode.OPEN);
		chooser.setSelectionMode(SelectionMode.FILES_AND_DIRECTORIES);
		chooser.setListener(new FileChooserListener() {
			@Override
			public void selected (Array<FileHandle> files) {
				if (listener != null) listener.selected(files);
			}

			@Override
			public void selected (FileHandle file) {
				if (listener != null) listener.selected(file);
			}

			@Override
			public void canceled () {
				if (listener != null) listener.canceled();
			}
		});
	}

	@Override
	public void resize () {
		chooser.centerWindow();
	}

	public void pickFileOrDirectory (FileChooserAdapter listener) {
		this.listener = listener;

		chooser.setMode(Mode.OPEN);
		chooser.setSelectionMode(SelectionMode.FILES_AND_DIRECTORIES);
		Editor.instance.getStage().addActor(chooser.fadeIn());
	}
}
