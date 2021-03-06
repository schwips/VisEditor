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

package com.kotcrab.vis.editor.module.project;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.tools.texturepacker.TexturePacker;
import com.badlogic.gdx.tools.texturepacker.TexturePacker.Settings;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.kotcrab.vis.editor.App;
import com.kotcrab.vis.editor.Editor;
import com.kotcrab.vis.editor.event.StatusBarEvent;
import com.kotcrab.vis.editor.module.InjectModule;
import com.kotcrab.vis.editor.module.editor.ObjectSupportModule;
import com.kotcrab.vis.editor.plugin.ObjectSupport;
import com.kotcrab.vis.editor.scene.*;
import com.kotcrab.vis.editor.ui.dialog.AsyncTaskProgressDialog;
import com.kotcrab.vis.editor.util.AsyncTask;
import com.kotcrab.vis.editor.util.Log;
import com.kotcrab.vis.runtime.assets.PathAsset;
import com.kotcrab.vis.runtime.data.*;
import com.kotcrab.vis.runtime.entity.Entity;
import com.kotcrab.vis.runtime.scene.SceneLoader;
import org.apache.commons.io.FileUtils;

import java.io.IOException;

public class ExportModule extends ProjectModule {
	@InjectModule private SceneCacheModule sceneCache;
	@InjectModule private ObjectSupportModule supportModule;

	private FileHandle visAssetsDir;

	private Settings texturePackerSettings;
	private boolean firstExportDone;

	private Json json;

	@Override
	public void init () {
		FileAccessModule fileAccess = projectContainer.get(FileAccessModule.class);

		visAssetsDir = fileAccess.getAssetsFolder();

		texturePackerSettings = new Settings();
		texturePackerSettings.combineSubdirectories = true;
		texturePackerSettings.silent = true;

		json = SceneLoader.getJson();
	}

	public void export (boolean quick) {
		switch (project.type) {
			case LibGDX:
				exportLibGDX(quick);
				break;
			case Generic:
				throw new UnsupportedOperationException("Not implemented yet");
		}
	}

	private void exportLibGDX (boolean quick) {
		if (firstExportDone == false && quick)
			Log.info("Requested quick export but normal export hasn't been done since editor launch, performing normal export.");

		if (firstExportDone == false || quick == false)
			doExport();
		else
			doExport(); //TODO do quick export

		firstExportDone = true;
	}

	private void doExport () {
		ExportAsyncTask exportTask = new ExportAsyncTask();
		Editor.instance.getStage().addActor(new AsyncTaskProgressDialog("Exporting", exportTask).fadeIn());
	}

	private class ExportAsyncTask extends AsyncTask {
		int step;
		int totalSteps;

		FileHandle outAssetsDir;

		/** Holds currently exported scene */
		private EditorScene editorScene;

		public ExportAsyncTask () {
			super("ProjectExporter");
		}

		@Override
		public void execute () {
			setMessage("Preparing for export...");
			totalSteps = calculateSteps();

			cleanOldAssets();
			packageTextures();
			copyAssets();
			exportScenes(this, visAssetsDir.child("scene"), outAssetsDir.child("scene"));

			nextStep();
			App.eventBus.post(new StatusBarEvent("Export finished"));
		}

		private void nextStep () {
			setProgressPercent(++step * 100 / totalSteps);
		}

		private int calculateSteps () {
			int steps = 0;
			steps++; //clean old assets, new dirs
			steps++; //package textures

			int assetsDirCounter = visAssetsDir.list(file -> {
				//exclude gfx and scene dir, exclude empty folders
				return file.isDirectory() && file.list().length > 0 && !(file.getName().equals("gfx") || file.getName().equals("scene"));
			}).length;
			steps += assetsDirCounter;

			String[] ext = {"scene"};
			int sceneCounter = FileUtils.listFiles(visAssetsDir.child("scene").file(), ext, true).size();
			steps += sceneCounter;

			return steps;
		}

		private void cleanOldAssets () {
			setMessage("Cleaning old assets");
			outAssetsDir = Gdx.files.absolute(project.root + project.assets);

			outAssetsDir.deleteDirectory();
			outAssetsDir.mkdirs();

			outAssetsDir.child("gfx").mkdirs();
			outAssetsDir.child("scene").mkdirs();
			nextStep();
		}

		private void packageTextures () {
			setMessage("Packaging textures");
			TexturePacker.process(texturePackerSettings, visAssetsDir.child("gfx").path(), outAssetsDir.child("gfx").path(), "textures");
			nextStep();
		}

		private void copyAssets () {
			for (FileHandle file : visAssetsDir.list()) {
				if (file.isDirectory() == false) continue;
				if (file.list().length == 0) continue;
				if (file.name().equals("gfx") || file.name().equals("scene")) continue;

				setMessage("Copying assets directory: " + file.name());

				try {
					FileUtils.copyDirectory(file.file(), outAssetsDir.child(file.name()).file());
				} catch (IOException e) {
					Log.exception(e);
				}
				nextStep();
			}
		}

		private void exportScenes (ExportAsyncTask task, FileHandle sceneDir, FileHandle outDir) {
			outDir.mkdirs();

			editorScene = null;

			for (final FileHandle file : sceneDir.list()) {
				if (file.isDirectory()) exportScenes(task, file, outDir.child(file.name()));

				if (file.extension().equals("scene")) {
					task.setMessage("Exporting scene: " + file.name());

					executeOnOpenGL(() -> editorScene = sceneCache.get(file));

					SceneData sceneData = new SceneData();

					sceneData.viewport = editorScene.viewport;
					sceneData.width = editorScene.width;
					sceneData.height = editorScene.height;
					sceneData.entities = new Array<>();

					for (EditorObject entity : editorScene.entities) {
						if (exportEntity(sceneData.entities, entity) == false)
							Log.error("Ignoring unknown entity: " + entity.getClass());
					}

					json.toJson(sceneData, outDir.child(file.name()));
					task.nextStep();

				} else
					Log.warn("Unknown file in 'scene' directory: " + file.path());
			}
		}

		private boolean exportEntity (Array<EntityData> entities, EditorObject entity) {
			if (entity instanceof SpriteObject) {
				SpriteObject obj = (SpriteObject) entity;

				SpriteData data = new SpriteData();
				data.saveFrom(obj);

				data.id = obj.getId();

				PathAsset pathAsset = (PathAsset) obj.getAssetDescriptor(); //FIXME
				String path = pathAsset.getPath();

				if (path.contains("*")) {
					String[] pathParts = path.split("\\*", 2);
					data.textureAtlas = pathParts[0];
					data.texturePath = pathParts[1];
				} else {
					data.textureAtlas = "gfx/textures.atlas";
					data.texturePath = path;
				}

				entities.add(data);
				return true;
			}

			if (entity instanceof TextObject) {
				TextObject obj = (TextObject) entity;

				TextData data = new TextData();
				data.id = obj.getId();
				data.saveFrom(obj);

				entities.add(data);
				return true;
			}

			if (entity instanceof ParticleObject) {
				ParticleObject obj = (ParticleObject) entity;

				ParticleEffectData data = new ParticleEffectData();
				data.id = obj.getId();
				data.saveFrom(obj);

				entities.add(data);
				return true;
			}

			if (entity instanceof MusicObject) {
				MusicObject obj = (MusicObject) entity;

				MusicData data = new MusicData();
				data.id = obj.getId();
				data.saveFrom(obj);

				entities.add(data);
				return true;
			}

			if (entity instanceof SoundObject) {
				SoundObject obj = (SoundObject) entity;

				SoundData data = new SoundData();
				data.id = obj.getId();
				data.saveFrom(obj);

				entities.add(data);
				return true;
			}

			if (entity instanceof ObjectGroup) {
				ObjectGroup group = (ObjectGroup) entity;

				if (group.isPreserveOnRuntime()) {
					EntityGroupData data = new EntityGroupData(group.getId());

					for (EditorObject object : group.getObjects())
						exportEntity(data.entities, object);

					entities.add(data);
				} else {
					for (EditorObject object : group.getObjects())
						exportEntity(entities, object);
				}

				return true;
			}

			ObjectSupport support = supportModule.get(entity.getClass());

			if (support != null) {
				support.export(ExportModule.this, entities, (Entity) entity);
				return true;
			}

			return false;
		}

	}

}
