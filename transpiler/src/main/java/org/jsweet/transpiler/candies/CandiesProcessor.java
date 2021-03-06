/* 
 * JSweet transpiler - http://www.jsweet.org
 * Copyright (C) 2015 CINCHEO SAS <renaud.pawlak@cincheo.fr>
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *  
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package org.jsweet.transpiler.candies;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.jsweet.JSweetConfig;
import org.jsweet.transpiler.JSweetProblem;
import org.jsweet.transpiler.TranspilationHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * The candies processor extracts and processes what is required from the candy
 * jars in order to ensure safe transpilation.
 * 
 * <ul>
 * <li>The embedded TypeScript definition files (*.d.ts)</li>
 * <li>Cross-candies mixins, which are merged by {@link CandiesMerger}</li>
 * </ul>
 * 
 * @author Louis Grignon
 */
public class CandiesProcessor {

	private static final Logger logger = Logger.getLogger(CandiesProcessor.class);
	private final static Gson gson = new GsonBuilder().setPrettyPrinting().create();

	private String classPath;

	/**
	 * The name of the directory that will contain the candies.
	 */
	public static final String CANDIES_DIR_NAME = "candies";
	/**
	 * This directory will contain the sources.
	 */
	public static final String CANDIES_SOURCES_DIR_NAME = CANDIES_DIR_NAME + File.separator + "src";
	/**
	 * The name of the file that stores processed candies info.
	 */
	public static final String CANDIES_STORE_FILE_NAME = CANDIES_DIR_NAME + File.separator
			+ CandiesStore.class.getSimpleName() + ".json";
	/**
	 * The name of the directory that contains the TypeScript source files.
	 */
	public static final String CANDIES_TSDEFS_DIR_NAME = CANDIES_DIR_NAME + File.separator
			+ JSweetConfig.TS_LIBS_DIR_NAME;
	/**
	 * Default directory for extracted candies' javascript.
	 */
	private static final String CANDIES_DEFAULT_JS_DIR_NAME = CANDIES_DIR_NAME + File.separator + "js";

	private File candiesSourceDir;
	private File candiesStoreFile;
	private File candiesTsdefsDir;
	private File candiesJavascriptOutDir;
	private File workingDir;

	/**
	 * Create a candies processor.
	 * 
	 * @param workingDir
	 *            the directory where the processor will save all cache and
	 *            temporary data for processing
	 * @param classPath
	 *            the classpath where the processor will seek for JSweet candies
	 * @param extractedCandiesJavascriptDir
	 *            see JSweetTranspiler.extractedCandyJavascriptDir
	 */
	public CandiesProcessor(File workingDir, String classPath, File extractedCandiesJavascriptDir) {
		this.workingDir = workingDir;
		this.classPath = (classPath == null ? System.getProperty("java.class.path") : classPath);
		String[] cp = this.classPath.split(File.pathSeparator);
		int[] indices = new int[0];
		for (int i = 0; i < cp.length; i++) {
			if (cp[i].replace('\\', '/').matches(".*org/jsweet/lib/.*-testbundle/.*/.*-testbundle-.*\\.jar")) {
				logger.warn("candies processor ignores classpath entry: " + cp[i]);
				indices = ArrayUtils.add(indices, i);
			}
		}
		cp = ArrayUtils.removeAll(cp, indices);
		this.classPath = StringUtils.join(cp, File.pathSeparator);
		logger.info("candies processor classpath: " + this.classPath);
		candiesSourceDir = new File(workingDir, CANDIES_SOURCES_DIR_NAME);
		candiesStoreFile = new File(workingDir, CANDIES_STORE_FILE_NAME);
		candiesTsdefsDir = new File(workingDir, CANDIES_TSDEFS_DIR_NAME);

		setCandiesJavascriptOutDir(extractedCandiesJavascriptDir);
	}

	private void setCandiesJavascriptOutDir(File extractedCandiesJavascriptDir) {
		this.candiesJavascriptOutDir = extractedCandiesJavascriptDir;
		if (this.candiesJavascriptOutDir == null) {
			logger.info("extracted candies directory is set to default");
			this.candiesJavascriptOutDir = new File(workingDir, CANDIES_DEFAULT_JS_DIR_NAME);
		}
		logger.info("extracted candies directory: " + extractedCandiesJavascriptDir);
		this.candiesJavascriptOutDir.mkdirs();
	}

	/**
	 * Returns the directory that contains the orginal TypeScript source code of
	 * the processed (merged) candies.
	 */
	public File getCandiesTsdefsDir() {
		return candiesTsdefsDir;
	}

	/**
	 * Do the processing for the candies jars found in the classpath.
	 */
	public void processCandies(TranspilationHandler transpilationHandler) throws IOException {
		CandiesStore candiesStore = getCandiesStore();

		LinkedHashMap<File, CandyDescriptor> newCandiesDescriptors = getCandiesDescriptorsFromClassPath(
				transpilationHandler);
		CandiesStore newStore = new CandiesStore(new ArrayList<>(newCandiesDescriptors.values()));
		if (newStore.equals(candiesStore)) {
			logger.info("candies are up to date");
			return;
		}

		this.candiesStore = newStore;
		logger.info("candies changed, processing candies: " + this.candiesStore);

		try {
			extractCandies(newCandiesDescriptors);

			writeCandiesStore();

		} catch (Throwable t) {
			logger.error("cannot generate candies bundle", t);
			// exit with fatal if no jar ?
		}
	}

	private LinkedHashMap<File, CandyDescriptor> getCandiesDescriptorsFromClassPath(
			TranspilationHandler transpilationHandler) throws IOException {
		LinkedHashMap<File, CandyDescriptor> jarFilesCollector = new LinkedHashMap<>();
		for (String classPathEntry : classPath.split("[" + System.getProperty("path.separator") + "]")) {
			if (classPathEntry.endsWith(".jar")) {
				File jarFile = new File(classPathEntry);
				try (JarFile jarFileHandle = new JarFile(jarFile)) {
					JarEntry candySpecificEntry = jarFileHandle
							.getJarEntry("META-INF/maven/" + JSweetConfig.MAVEN_CANDIES_GROUP);
					JarEntry candySpecificEntry2 = jarFileHandle.getJarEntry("META-INF/candy-metadata.json");
					boolean isCandy = candySpecificEntry != null || candySpecificEntry2 != null;
					if (isCandy) {
						CandyDescriptor descriptor = CandyDescriptor.fromCandyJar(jarFileHandle,
								candiesJavascriptOutDir.getAbsolutePath());

						checkCandyVersion(descriptor, transpilationHandler);
						jarFilesCollector.put(jarFile, descriptor);
					}
				}

			}
		}
		logger.info(jarFilesCollector.keySet().size() + " candies found in classpath");

		return jarFilesCollector;
	}

	private void checkCandyVersion(CandyDescriptor candy, TranspilationHandler transpilationHandler) {

		String actualTranspilerVersion = JSweetConfig.getVersionNumber().split("-")[0];
		String candyTranspilerVersion = candy.transpilerVersion == null ? null : candy.transpilerVersion.split("-")[0];

		if (candyTranspilerVersion == null || !candyTranspilerVersion.equals(actualTranspilerVersion)) {
			transpilationHandler.report(JSweetProblem.CANDY_VERSION_DISCREPANCY, null,
					JSweetProblem.CANDY_VERSION_DISCREPANCY.getMessage(candy.name, candy.version,
							actualTranspilerVersion, candyTranspilerVersion));
		}
	}

	private void extractCandies(Map<File, CandyDescriptor> candies) throws IOException {
		File extractedSourcesDir = candiesSourceDir;
		File extractedTsDefsDir = candiesTsdefsDir;
		FileUtils.deleteQuietly(extractedSourcesDir);
		FileUtils.deleteQuietly(extractedTsDefsDir);
		extractedSourcesDir.mkdirs();
		extractedTsDefsDir.mkdirs();
		for (Map.Entry<File, CandyDescriptor> candy : candies.entrySet()) {

			CandyDescriptor candyDescriptor = candy.getValue();
			File jarFile = candy.getKey();

			String candyName = candyDescriptor.name;
			boolean isCore = "jsweet-core".equals(candyName);
			try (JarFile jarFileHandle = new JarFile(jarFile)) {
				String candyJarName = FilenameUtils.getBaseName(jarFile.getName());
				File candyExtractedSourcesDir = new File(extractedSourcesDir, candyJarName);
				File candyExtractedJsDir = new File(candiesJavascriptOutDir, candyJarName);

				extractCandy( //
						candyDescriptor, //
						jarFileHandle, //
						candyExtractedSourcesDir, //
						extractedTsDefsDir, //
						candyExtractedJsDir, //
						isCore ? tsDefName -> false : null);
			}
		}
	}

	private void extractCandy( //
			CandyDescriptor descriptor, //
			JarFile jarFile, //
			File javaOutputDirectory, //
			File tsDefOutputDirectory, //
			File jsOutputDirectory, //
			Predicate<String> isTsDefToBeExtracted) {
		logger.info("extract candy: " + jarFile.getName() + " javaOutputDirectory=" + javaOutputDirectory
				+ " tsDefOutputDirectory=" + tsDefOutputDirectory + " jsOutputDir=" + jsOutputDirectory);

		jarFile.stream()
				.filter(entry -> entry.getName().endsWith(".d.ts")
						&& (entry.getName().startsWith("src/") || entry.getName().startsWith("META-INF/resources/"))) //
				.forEach(entry -> {

					File out;
					if (entry.getName().endsWith(".java")) {
						// RP: this looks like dead code...
						out = new File(javaOutputDirectory + "/" + entry.getName().substring(4));
					} else if (entry.getName().endsWith(".d.ts")) {
						if (isTsDefToBeExtracted != null && !isTsDefToBeExtracted.test(entry.getName())) {
							return;
						}
						out = new File(tsDefOutputDirectory + "/" + entry.getName());
					} else {
						out = null;
					}
					extractEntry(jarFile, entry, out);
				});

		for (String jsFilePath : descriptor.jsFilesPaths) {
			JarEntry entry = jarFile.getJarEntry(jsFilePath);
			String relativeJsPath = jsFilePath.substring(descriptor.jsDirPath.length());

			File out = new File(jsOutputDirectory, relativeJsPath);
			extractEntry(jarFile, entry, out);
		}
	}

	private void extractEntry(JarFile jarFile, JarEntry entry, File out) {
		if (out == null) {
			return;
		}
		out.getParentFile().mkdirs();
		try {
			FileUtils.copyInputStreamToFile(jarFile.getInputStream(entry), out);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private CandiesStore candiesStore;

	/**
	 * Cleans the candies store so that it will be read from file next time.
	 */
	public void touch() {
		candiesStore = null;
	}

	private CandiesStore getCandiesStore() {
		if (candiesStore == null) {
			if (candiesStoreFile.exists()) {
				try {
					candiesStore = gson.fromJson(FileUtils.readFileToString(candiesStoreFile), CandiesStore.class);
				} catch (Exception e) {
					logger.error("cannot read candies index", e);
				}
			}

			if (candiesStore == null) {
				candiesStore = new CandiesStore();
			}
		}

		return candiesStore;
	}

	private void writeCandiesStore() {
		if (candiesStore != null) {
			try {
				FileUtils.write(candiesStoreFile, gson.toJson(candiesStore));
			} catch (Exception e) {
				logger.error("cannot read candies index", e);
			}
		}
	}

}
