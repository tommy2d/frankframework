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
package org.frankframework.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import org.frankframework.configuration.filters.ClassNameAliases;
import org.frankframework.configuration.filters.ClassNameOverrides;
import org.frankframework.configuration.filters.ClassNameRewriter;
import org.frankframework.pipes.EchoPipe;
import org.frankframework.testutil.MatchUtils;
import org.frankframework.testutil.TestConfiguration;
import org.frankframework.util.XmlUtils;
import org.frankframework.xml.XmlWriter;

class ClassNameOverridesTest {

	private static final String ELEMENT_TEMPLATE = "<pipe className=\"%s\" />";

	private String rewrite(Configuration configuration, String className) throws Exception {
		XmlWriter writer = new XmlWriter();
		ClassNameRewriter handler = new ClassNameRewriter(writer, configuration, false,
				ClassNameAliases.getInstance(), ClassNameOverrides.getInstance());
		XmlUtils.parseXml(ELEMENT_TEMPLATE.formatted(className), handler);
		return writer.toString();
	}

	@Test
	void overriddenClassIsReplaced() throws Exception {
		Configuration configuration = new TestConfiguration();

		String result = rewrite(configuration, "org.frankframework.test.overrides.ReplaceMePipe");

		MatchUtils.assertXmlEquals(ELEMENT_TEMPLATE.formatted(EchoPipe.class.getCanonicalName()), result);
	}

	@Test
	void overrideAddsNoConfigurationWarning() throws Exception {
		Configuration configuration = new TestConfiguration();

		rewrite(configuration, "org.frankframework.test.overrides.ReplaceMePipe");

		assertTrue(configuration.getConfigurationWarnings().getWarnings().isEmpty(),
				"an override is not a deprecation, so it must not warn: " + configuration.getConfigurationWarnings().getWarnings());
	}

	@Test
	void unknownClassIsLeftAlone() throws Exception {
		Configuration configuration = new TestConfiguration();

		String result = rewrite(configuration, EchoPipe.class.getCanonicalName());

		MatchUtils.assertXmlEquals(ELEMENT_TEMPLATE.formatted(EchoPipe.class.getCanonicalName()), result);
	}

	@Test
	void unloadableOverrideKeepsTheOriginalAndDoesNotBlameTheConfiguration() throws Exception {
		Configuration configuration = new TestConfiguration();
		String original = "org.frankframework.test.overrides.BrokenPipe";

		String result = rewrite(configuration, original);

		MatchUtils.assertXmlEquals(ELEMENT_TEMPLATE.formatted(original), result);
		assertTrue(configuration.getConfigurationWarnings().getWarnings().isEmpty(),
				"a deployment shipping a broken override is not the configuration's fault: "
						+ configuration.getConfigurationWarnings().getWarnings());
	}

	@Test
	void noneHasNoOverrides() {
		assertTrue(ClassNameOverrides.none().isEmpty());
		assertTrue(ClassNameOverrides.none().target("anything").isEmpty());
	}

	@Test
	void theTestClassPathCarriesOverrides() {
		assertFalse(ClassNameOverrides.getInstance().isEmpty());
		assertEquals(EchoPipe.class.getCanonicalName(),
				ClassNameOverrides.getInstance().target("org.frankframework.test.overrides.ReplaceMePipe").orElseThrow());
	}
}
