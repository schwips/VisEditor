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

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import com.kotcrab.vis.editor.App;
import com.kotcrab.vis.editor.Editor;
import com.kotcrab.vis.editor.module.InjectModule;
import com.kotcrab.vis.editor.plugin.ContainerExtension;
import com.kotcrab.vis.editor.plugin.FailedPluginDescriptor;
import com.kotcrab.vis.editor.plugin.ObjectSupport;
import com.kotcrab.vis.editor.plugin.PluginDescriptor;
import com.kotcrab.vis.editor.ui.dialog.LicenseDialog;
import com.kotcrab.vis.editor.ui.dialog.LicenseDialog.LicenseDialogListener;
import com.kotcrab.vis.editor.ui.toast.ExceptionToast;
import com.kotcrab.vis.editor.ui.toast.LoadingPluginsFailedToast;
import com.kotcrab.vis.editor.util.ChildFirstURLClassLoader;
import com.kotcrab.vis.editor.util.EditorException;
import com.kotcrab.vis.editor.util.FileUtils;
import com.kotcrab.vis.editor.util.Log;
import com.kotcrab.vis.editor.util.gdx.ButtonUtils;
import com.kotcrab.vis.editor.util.gdx.VisChangeListener;
import com.kotcrab.vis.runtime.plugin.EntitySupport;
import com.kotcrab.vis.runtime.plugin.VisPlugin;
import com.kotcrab.vis.ui.widget.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

public class PluginLoaderModule extends EditorModule {
	private static final String TAG = "PluginLoader";
	private static final String PLUGINS_FOLDER_PATH = App.getJarFolderPath() + File.separator + "plugins";

	@InjectModule private PluginContainerModule pluginContainer;
	@InjectModule private PluginSettingsModule settings;
	@InjectModule private ToastModule toastModule;

	//we need nested iterators, Array can't provide that
	private Array<PluginDescriptor> allPlugins = new Array<>();
	private Array<PluginDescriptor> pluginsToLoad;

	private Array<FailedPluginDescriptor> failedPlugins = new Array<>();

	@Override
	public void postInit () {
		FileHandle pluginsFolder = Gdx.files.absolute(PLUGINS_FOLDER_PATH);
		Log.debug(TAG, "Loading plugins from: " + pluginsFolder);

		try {
			loadPluginsDescriptors(new Array<>(pluginsFolder.list()));
			verifyPlugins();
			loadPluginsJars();
			loadMainPluginsClasses();
		} catch (IOException | ReflectiveOperationException e) {
			Log.exception(e);
			toastModule.show(new ExceptionToast("Plugin loading failed!", e));
		}

		if (failedPlugins.size > 0)
			toastModule.show(new LoadingPluginsFailedToast(failedPlugins));
	}

	private void loadPluginsDescriptors (Array<FileHandle> pluginsFolders) throws IOException {
		for (FileHandle folder : pluginsFolders) {

			FileHandle[] files = folder.list((dir, name) -> name.endsWith("jar"));
			if (files.length > 1 || files.length == 0) {
				Log.error(TAG, "Failed (invalid directory structure): " + folder.name());
				failedPlugins.add(new FailedPluginDescriptor(folder,
						new IllegalStateException("Plugin directory must contain only one jar (requried libs must be stored in 'lib' subdirectory")));
				continue;
			}

			FileHandle pluginJar = files[0];

			try {
				JarInputStream jarStream = new JarInputStream(new FileInputStream(pluginJar.file()));
				Manifest mf = jarStream.getManifest();
				jarStream.close();

				PluginDescriptor desc = new PluginDescriptor(pluginJar, mf);
				allPlugins.add(desc);
			} catch (IOException e) {
				Log.error(TAG, "Failed (IO exception): " + folder.name());
				failedPlugins.add(new FailedPluginDescriptor(folder, e));
				Log.exception(e);
			} catch (EditorException e) {
				Log.error(TAG, "Failed (invalid manifest): " + folder.name());
				failedPlugins.add(new FailedPluginDescriptor(folder, e));
				Log.exception(e);
			}
		}
	}

	private void verifyPlugins () {
		pluginsToLoad = new Array<>(allPlugins);

		Iterator<PluginDescriptor> it = pluginsToLoad.iterator();
		while (it.hasNext()) {
			PluginDescriptor descriptor = it.next();

			if (settings.config.pluginsIdsToLoad.contains(descriptor.folderName, false) == false) {
				Log.debug(TAG, "Skipping: " + descriptor.folderName);
				it.remove();
			}
		}
	}

	private void loadPluginsJars () throws IOException, ClassNotFoundException {
		Array<URL> urls = new Array<>();

		for (PluginDescriptor descriptor : pluginsToLoad) {
			for (FileHandle lib : descriptor.libs)
				urls.add(new URL("jar:file:" + lib.path() + "!/"));

			urls.add(new URL("jar:file:" + descriptor.file.path() + "!/"));
		}

		URLClassLoader classLoader = ChildFirstURLClassLoader.newInstance(urls.toArray(URL.class), Thread.currentThread().getContextClassLoader());
		Thread.currentThread().setContextClassLoader(classLoader);

		for (PluginDescriptor descriptor : pluginsToLoad) {
			Log.debug(TAG, "Loading: " + descriptor.folderName);

			JarFile jarFile = new JarFile(descriptor.file.path());
			loadJarClasses(classLoader, descriptor, jarFile.entries());
		}
	}

	private void loadJarClasses (URLClassLoader classLoader, PluginDescriptor descriptor, Enumeration<JarEntry> entries) throws ClassNotFoundException {
		while (entries.hasMoreElements()) {
			JarEntry entry = entries.nextElement();
			if (entry.isDirectory() || entry.getName().endsWith(".class") == false) continue;

			String className = entry.getName().substring(0, entry.getName().length() - ".class".length());
			className = className.replace('/', '.');
			Class<?> clazz = classLoader.loadClass(className);

			if (clazz.getAnnotation(VisPlugin.class) != null)
				descriptor.pluginClasses.add(clazz);
		}
	}

	private void loadMainPluginsClasses () throws ReflectiveOperationException {
		for (PluginDescriptor descriptor : pluginsToLoad) {

			for (Class<?> clazz : descriptor.pluginClasses) {
				Constructor<?> cons = clazz.getConstructor();
				Object object = cons.newInstance();

				if (object instanceof ObjectSupport) {
					pluginContainer.addSupport((ObjectSupport) object);
					continue;
				}

				if (object instanceof ContainerExtension) {
					pluginContainer.addContainerExtension((ContainerExtension) object);
					continue;
				}

				if (object instanceof EntitySupport)
					continue;

				Log.warn("Plugin '" + descriptor.folderName + "' was successfully loaded but it's plugin class '" + clazz.getSimpleName() + "' object wasn't recognized.");
			}
		}
	}

	private Array<PluginDescriptor> getAllPlugins () {
		return allPlugins;
	}

	public static class PluginSettingsModule extends EditorSettingsModule<PluginsConfig> {
		private PluginLoaderModule loader;

		private ObjectMap<String, VisCheckBox> pluginsCheckBoxes = new ObjectMap<>();
		private VisTable pluginsTable;

		public PluginSettingsModule () {
			super("Plugins", "pluginsSettings", PluginsConfig.class);
		}

		@Override
		public void init () {
			super.init();
			loader = container.get(PluginLoaderModule.class);
		}

		@Override
		public void buildTable () {
			pluginsTable = new VisTable(false);

			VisScrollPane scrollPane = new VisScrollPane(pluginsTable);

			VisTextButton openPluginFolderButton = new VisTextButton("Open Plugin Folder");
			openPluginFolderButton.addListener(new VisChangeListener((event, actor) -> FileUtils.browse(Gdx.files.absolute(PLUGINS_FOLDER_PATH))));

			prepareTable();

			VisTable topTable = new VisTable(true);

			topTable.add(new VisLabel("Plugins"));
			topTable.add().expandX().fillX();
			topTable.add(openPluginFolderButton);

			settingsTable.add(topTable).expandX().fillX().row();
			settingsTable.add(scrollPane).expand().fill().row();
			settingsTable.add("Editor restart will be required to apply changes!").padBottom(3);
		}

		@Override
		protected void onShow () {
			pluginsTable.clearChildren();
			pluginsTable.left().top();
			pluginsTable.defaults().left();
			pluginsCheckBoxes.clear();

			for (PluginDescriptor descriptor : loader.getAllPlugins()) {
				VisCheckBox checkBox = new VisCheckBox(descriptor.folderName);
				ButtonUtils.disableProgrammaticEvents(checkBox);

				pluginsTable.add(checkBox).row();
				pluginsCheckBoxes.put(descriptor.folderName, checkBox);

				if (descriptor.license != null) {
					checkBox.addListener(new VisChangeListener((event, actor) -> {
						if (checkBox.isChecked() && event.isStopped() == false) {
							checkBox.setChecked(false);

							Editor.instance.getStage().addActor(new LicenseDialog(descriptor.license, new LicenseDialogListener() {
								@Override
								public void licenseDeclined () {
									checkBox.setChecked(false);
								}

								@Override
								public void licenseAccepted () {
									checkBox.setChecked(true);
								}
							}));
						}
					}));
				}
			}

			Iterator<String> it = config.pluginsIdsToLoad.iterator();
			while (it.hasNext()) {
				String plugin = it.next();

				if (pluginsCheckBoxes.get(plugin) == null) {
					it.remove();
					continue;
				}

				pluginsCheckBoxes.get(plugin).setChecked(true);
			}
		}

		@Override
		public void settingsApply () {
			config.pluginsIdsToLoad.clear();
			for (VisCheckBox checkBox : pluginsCheckBoxes.values())
				if (checkBox.isChecked()) config.pluginsIdsToLoad.add(checkBox.getText().toString());

			settingsSave();
		}

		public boolean isPluginEnabled (String folderName) {
			return config.pluginsIdsToLoad.contains(folderName, false);
		}

		public void enablePlugin (String folderName) {
			config.pluginsIdsToLoad.add(folderName);
			settingsSave();
		}

		@Override
		public void loadConfigToTable () {
		} //unused, we load config in onShow
	}

	public static class PluginsConfig {
		private Array<String> pluginsIdsToLoad = new Array<>();

		//this constructor will be called if save file does not exist
		public PluginsConfig () {
			//by default load spine notifier
			pluginsIdsToLoad.add("spine-notifier");
		}
	}

}
