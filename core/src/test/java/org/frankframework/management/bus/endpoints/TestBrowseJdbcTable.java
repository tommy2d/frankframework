package org.frankframework.management.bus.endpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URL;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import org.frankframework.management.bus.BusAction;
import org.frankframework.management.bus.BusException;
import org.frankframework.management.bus.BusTestBase;
import org.frankframework.management.bus.BusTopic;
import org.frankframework.stream.UrlMessage;
import org.frankframework.testutil.MatchUtils;
import org.frankframework.testutil.SpringRootInitializer;
import org.frankframework.testutil.TestFileUtils;
import org.frankframework.util.AppConstants;

@SpringJUnitConfig(initializers = {SpringRootInitializer.class})
@WithMockUser(roles = { "IbisTester" })
public class TestBrowseJdbcTable extends BusTestBase {
	private static final String PERMISSION_RULES_KEY = "browseJdbcTable.permission.rules";

	private org.frankframework.stream.Message inputXmlMessage;
	private String originalPermissionRules;

	@BeforeEach
	public void setup() {
		URL url = TestFileUtils.getTestFileURL("/Management/BrowseJdbcTable/request.xml");
		inputXmlMessage = new UrlMessage(url);
		originalPermissionRules = AppConstants.getInstance().getProperty(PERMISSION_RULES_KEY);
	}

	@AfterEach
	public void restorePermissionRules() {
		AppConstants.getInstance().setProperty(PERMISSION_RULES_KEY, originalPermissionRules);
	}

	@Test
	public void testBrowseTableWithoutTablename() {
		mockDirectQuerySenderResult("BrowseTable QuerySender", inputXmlMessage);

		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.JDBC, BusAction.FIND);
		Exception e = assertThrows(Exception.class, () -> callSyncGateway(request));
		BusException be = assertInstanceOf(BusException.class, e.getCause());
		assertEquals("Access to table [null] not allowed", be.getMessage());
	}

	@Test
	public void testBrowseTable() throws Exception {
		mockDirectQuerySenderResult("BrowseTable QuerySender", inputXmlMessage);

		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.JDBC, BusAction.FIND);
		request.setHeader("table", "testTable");
		Message<?> response = callSyncGateway(request);
		String expectedJson = TestFileUtils.getTestFile("/Management/BrowseJdbcTable/default.json");
		MatchUtils.assertJsonEquals(expectedJson, (String) response.getPayload());
	}

	@Test
	public void testBrowseTableNumberOfRowsOnly() throws Exception {
		mockDirectQuerySenderResult("BrowseTable QuerySender", inputXmlMessage);

		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.JDBC, BusAction.FIND);
		request.setHeader("numberOfRowsOnly", true);
		request.setHeader("table", "testTable");
		Message<?> response = callSyncGateway(request);
		String expectedJson = TestFileUtils.getTestFile("/Management/BrowseJdbcTable/nrOfRows.json");
		MatchUtils.assertJsonEquals(expectedJson, (String) response.getPayload());
	}

	@Test
	public void testBrowseTableOrderBy() throws Exception {
		mockDirectQuerySenderResult("BrowseTable QuerySender", inputXmlMessage);

		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.JDBC, BusAction.FIND);
		request.setHeader("order", "date");
		request.setHeader("table", "testTable");
		Message<?> response = callSyncGateway(request);
		String expectedJson = TestFileUtils.getTestFile("/Management/BrowseJdbcTable/orderBy.json");
		MatchUtils.assertJsonEquals(expectedJson, (String) response.getPayload());
	}

	@Test
	public void testBrowseTableWhere() throws Exception {
		mockDirectQuerySenderResult("BrowseTable QuerySender", inputXmlMessage);

		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.JDBC, BusAction.FIND);
		request.setHeader("where", "something=\"true\"");
		request.setHeader("table", "testTable");
		Message<?> response = callSyncGateway(request);
		String expectedJson = TestFileUtils.getTestFile("/Management/BrowseJdbcTable/where.json");
		MatchUtils.assertJsonEquals(expectedJson, (String) response.getPayload());
	}

	@Test
	public void testBrowseTableMaxSmallerThenMin() {
		mockDirectQuerySenderResult("BrowseTable QuerySender", inputXmlMessage);

		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.JDBC, BusAction.FIND);
		request.setHeader("table", "testTable");
		request.setHeader("minRow", 10);
		request.setHeader("maxRow", 1);

		Exception e = assertThrows(Exception.class, () -> callSyncGateway(request));
		BusException be = assertInstanceOf(BusException.class, e.getCause());
		assertEquals("Rownum max must be greater than or equal to Rownum min", be.getMessage());
	}

	@Test
	public void testBrowseTableMaxMoreThen100() {
		mockDirectQuerySenderResult("BrowseTable QuerySender", inputXmlMessage);

		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.JDBC, BusAction.FIND);
		request.setHeader("table", "testTable");
		request.setHeader("minRow", 1);
		request.setHeader("maxRow", 101);

		Exception e = assertThrows(Exception.class, () -> callSyncGateway(request));
		BusException be = assertInstanceOf(BusException.class, e.getCause());
		assertEquals("Difference between Rownum max and Rownum min must be less than hundred", be.getMessage());
	}

	@Test
	public void noTableIsBrowsableWithoutPermissionRules() {
		AppConstants.getInstance().setProperty(PERMISSION_RULES_KEY, ""); // the shipped default
		mockDirectQuerySenderResult("BrowseTable QuerySender", inputXmlMessage);

		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.JDBC, BusAction.FIND);
		request.setHeader("table", "testTable");

		Exception e = assertThrows(Exception.class, () -> callSyncGateway(request));
		BusException be = assertInstanceOf(BusException.class, e.getCause());
		assertEquals("Access to table [testTable] not allowed", be.getMessage());
	}

	@Test
	public void onlyATableNamedInTheRulesIsBrowsable() throws Exception {
		AppConstants.getInstance().setProperty(PERMISSION_RULES_KEY, "IBISSTORE IbisTester allow");
		mockDirectQuerySenderResult("BrowseTable QuerySender", inputXmlMessage);

		MessageBuilder<String> allowed = createRequestMessage("NONE", BusTopic.JDBC, BusAction.FIND);
		allowed.setHeader("table", "IBISSTORE");
		Message<?> response = callSyncGateway(allowed);
		assertInstanceOf(String.class, response.getPayload());

		MessageBuilder<String> denied = createRequestMessage("NONE", BusTopic.JDBC, BusAction.FIND);
		denied.setHeader("table", "testTable");
		Exception e = assertThrows(Exception.class, () -> callSyncGateway(denied));
		BusException be = assertInstanceOf(BusException.class, e.getCause());
		assertEquals("Access to table [testTable] not allowed", be.getMessage());
	}

	@Test
	public void aRuleForAnotherRoleDoesNotGrantAccess() {
		AppConstants.getInstance().setProperty(PERMISSION_RULES_KEY, "testTable IbisObserver allow");
		mockDirectQuerySenderResult("BrowseTable QuerySender", inputXmlMessage);

		MessageBuilder<String> request = createRequestMessage("NONE", BusTopic.JDBC, BusAction.FIND);
		request.setHeader("table", "testTable");

		Exception e = assertThrows(Exception.class, () -> callSyncGateway(request));
		BusException be = assertInstanceOf(BusException.class, e.getCause());
		assertEquals("Access to table [testTable] not allowed", be.getMessage());
	}
}
