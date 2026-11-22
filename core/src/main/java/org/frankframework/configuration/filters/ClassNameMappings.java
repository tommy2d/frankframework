/*
   Copyright 2026 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package org.frankframework.configuration.filters;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.jspecify.annotations.NonNull;

import lombok.extern.log4j.Log4j2;

/**
 * Reads and merges the class name mapping files that {@link ClassNameAliases} and {@link ClassNameOverrides}
 * are built from. The first file found wins for a given name, and a conflict names both files.
 */
@Log4j2
final class ClassNameMappings {

	private ClassNameMappings() {
		// static utility
	}

	static Map<String, String> load(@NonNull ClassLoader classLoader, @NonNull String resource, @NonNull String what) {
		Map<String, String> merged = new HashMap<>();
		Map<String, URL> origin = new HashMap<>();
		try {
			Enumeration<URL> resources = classLoader.getResources(resource);
			while (resources.hasMoreElements()) {
				URL url = resources.nextElement();
				Properties properties = new Properties();
				try (InputStream in = url.openStream()) {
					properties.load(in);
				}
				for (String oldName : properties.stringPropertyNames()) {
					String newName = properties.getProperty(oldName).trim();
					String known = merged.get(oldName);
					if (known == null) {
						merged.put(oldName, newName);
						origin.put(oldName, url);
					} else if (!known.equals(newName)) {
						log.warn("{} [{}] maps to [{}] in [{}] and to [{}] in [{}]; using [{}]",
								what, oldName, known, origin.get(oldName), newName, url, known);
					}
				}
			}
		} catch (IOException e) {
			log.warn("unable to read {}s [{}]", what, resource, e);
		}
		log.debug("loaded {} {}(s)", merged.size(), what);
		return merged;
	}
}
