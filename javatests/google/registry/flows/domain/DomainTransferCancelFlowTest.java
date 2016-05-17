// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.flows.domain;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.assertBillingEvents;
import static google.registry.testing.DatastoreHelper.createPollMessageForImplicitTransfer;
import static google.registry.testing.DatastoreHelper.deleteResource;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.getPollMessages;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DomainResourceSubject.assertAboutDomains;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.ResourceMutateFlow.ResourceToMutateDoesNotExistException;
import google.registry.flows.ResourceMutatePendingTransferFlow.NotPendingTransferException;
import google.registry.flows.ResourceTransferCancelFlow.NotTransferInitiatorException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.model.transfer.TransferStatus;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DomainTransferCancelFlow}. */
public class DomainTransferCancelFlowTest
    extends DomainTransferFlowTestCase<DomainTransferCancelFlow, DomainResource> {

  @Before
  public void setUp() throws Exception {
    setEppInput("domain_transfer_cancel.xml");
    setClientIdForFlow("NewRegistrar");
    setupDomainWithPendingTransfer();
  }

  private void doSuccessfulTest(String commandFilename, String expectedXmlFilename)
      throws Exception {
    setEppInput(commandFilename);

    // Replace the ROID in the xml file with the one generated in our test.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    // Make sure the implicit billing event is there; it will be deleted by the flow.
    // We also expect to see autorenew events for the gaining and losing registrars.
    assertBillingEvents(
        getBillingEventForImplicitTransfer(),
        getGainingClientAutorenewEvent(),
        getLosingClientAutorenewEvent());
    // We should see poll messages for the implicit ack case going to both registrars, and an
    // autorenew poll message for the new registrar.
    HistoryEntry historyEntryDomainTransferRequest =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST);
    assertPollMessages(
        "NewRegistrar",
        new PollMessage.Autorenew.Builder()
            .setTargetId(getUniqueIdFromCommand())
            .setClientId("NewRegistrar")
            .setEventTime(EXTENDED_REGISTRATION_EXPIRATION_TIME)
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setParent(historyEntryDomainTransferRequest)
            .build(),
        createPollMessageForImplicitTransfer(
            domain,
            historyEntryDomainTransferRequest,
            "NewRegistrar",
            TRANSFER_REQUEST_TIME,
            TRANSFER_EXPIRATION_TIME,
            TRANSFER_REQUEST_TIME));
    assertPollMessages(
        "TheRegistrar",
        createPollMessageForImplicitTransfer(
            domain,
            historyEntryDomainTransferRequest,
            "TheRegistrar",
            TRANSFER_REQUEST_TIME,
            TRANSFER_EXPIRATION_TIME,
            TRANSFER_REQUEST_TIME));
    clock.advanceOneMilli();

    // Setup done; run the test.
    assertTransactionalFlow(true);
    DateTime originalExpirationTime = domain.getRegistrationExpirationTime();
    ImmutableSet<GracePeriod> originalGracePeriods = domain.getGracePeriods();
    runFlowAssertResponse(readFile(expectedXmlFilename));

    // Transfer should have been cancelled. Verify correct fields were set.
    domain = reloadResourceByUniqueId();
    assertTransferFailed(domain, TransferStatus.CLIENT_CANCELLED);
    assertAboutDomains().that(domain)
        .hasRegistrationExpirationTime(originalExpirationTime).and()
        .hasLastTransferTimeNotEqualTo(clock.nowUtc());
    assertTransferFailed(
        reloadResourceAndCloneAtTime(subordinateHost, clock.nowUtc()),
        TransferStatus.CLIENT_CANCELLED);
    assertAboutDomains().that(domain).hasOneHistoryEntryEachOfTypes(
        HistoryEntry.Type.DOMAIN_CREATE,
        HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST,
        HistoryEntry.Type.DOMAIN_TRANSFER_CANCEL);
    // The only billing event left should be the original autorenew event, now reopened.
    assertBillingEvents(
        getLosingClientAutorenewEvent().asBuilder().setRecurrenceEndTime(END_OF_TIME).build());
    // The poll message (in the future) to the gaining registrar for implicit ack should be gone.
    assertThat(getPollMessages("NewRegistrar", clock.nowUtc().plusMonths(1)))
        .isEmpty();
    // The poll message in the future to the losing registrar should be gone too, but there should
    // be two at the current time to the losing registrar - one for the original autorenew event,
    // and another for the transfer being cancelled.
    assertPollMessages(
        "TheRegistrar",
        new PollMessage.Autorenew.Builder()
            .setTargetId(getUniqueIdFromCommand())
            .setClientId("TheRegistrar")
            .setEventTime(originalExpirationTime)
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setParent(getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_CREATE))
            .build(),
        new PollMessage.OneTime.Builder()
            .setClientId("TheRegistrar")
            .setEventTime(clock.nowUtc())
            .setResponseData(ImmutableList.of(new DomainTransferResponse.Builder()
                .setFullyQualifiedDomainNameName(getUniqueIdFromCommand())
                .setTransferStatus(TransferStatus.CLIENT_CANCELLED)
                .setTransferRequestTime(TRANSFER_REQUEST_TIME)
                .setGainingClientId("NewRegistrar")
                .setLosingClientId("TheRegistrar")
                .setPendingTransferExpirationTime(clock.nowUtc())
                .build()))
            .setMsg("Transfer cancelled.")
            .setParent(getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_TRANSFER_CANCEL))
            .build());

    // The original grace periods should remain untouched.
    assertThat(domain.getGracePeriods()).containsExactlyElementsIn(originalGracePeriods);
  }

  private void doFailingTest(String commandFilename) throws Exception {
    setEppInput(commandFilename);
    // Replace the ROID in the xml file with the one generated in our test.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlow();
  }

  @Test
  public void testDryRun() throws Exception {
    setEppInput("domain_transfer_cancel.xml");
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    dryRunFlowAssertResponse(readFile("domain_transfer_cancel_response.xml"));
  }

  @Test
  public void testSuccess() throws Exception {
    doSuccessfulTest("domain_transfer_cancel.xml", "domain_transfer_cancel_response.xml");
  }

  @Test
  public void testSuccess_domainAuthInfo() throws Exception {
    doSuccessfulTest("domain_transfer_cancel_domain_authinfo.xml",
        "domain_transfer_cancel_response.xml");
  }

  @Test
  public void testSuccess_contactAuthInfo() throws Exception {
    doSuccessfulTest("domain_transfer_cancel_contact_authinfo.xml",
        "domain_transfer_cancel_response.xml");
  }

  @Test
  public void testFailure_badContactPassword() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    // Change the contact's password so it does not match the password in the file.
    contact = persistResource(
        contact.asBuilder()
            .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("badpassword")))
            .build());
    doFailingTest("domain_transfer_cancel_contact_authinfo.xml");
  }

  @Test
  public void testFailure_badDomainPassword() throws Exception {
    thrown.expect(BadAuthInfoForResourceException.class);
    // Change the domain's password so it does not match the password in the file.
    domain = persistResource(domain.asBuilder()
        .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("badpassword")))
        .build());
    doFailingTest("domain_transfer_cancel_domain_authinfo.xml");
  }

  @Test
  public void testFailure_neverBeenTransferred() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(null);
    doFailingTest("domain_transfer_cancel.xml");
  }

  @Test
  public void testFailure_clientApproved() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.CLIENT_APPROVED);
    doFailingTest("domain_transfer_cancel.xml");
  }

 @Test
  public void testFailure_clientRejected() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.CLIENT_REJECTED);
    doFailingTest("domain_transfer_cancel.xml");
  }

 @Test
  public void testFailure_clientCancelled() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.CLIENT_CANCELLED);
    doFailingTest("domain_transfer_cancel.xml");
  }

  @Test
  public void testFailure_serverApproved() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.SERVER_APPROVED);
    doFailingTest("domain_transfer_cancel.xml");
  }

  @Test
  public void testFailure_serverCancelled() throws Exception {
    thrown.expect(NotPendingTransferException.class);
    changeTransferStatus(TransferStatus.SERVER_CANCELLED);
    doFailingTest("domain_transfer_cancel.xml");
  }

  @Test
  public void testFailure_sponsoringClient() throws Exception {
    thrown.expect(NotTransferInitiatorException.class);
    setClientIdForFlow("TheRegistrar");
    doFailingTest("domain_transfer_cancel.xml");
  }

  @Test
  public void testFailure_unrelatedClient() throws Exception {
    thrown.expect(NotTransferInitiatorException.class);
    setClientIdForFlow("ClientZ");
    doFailingTest("domain_transfer_cancel.xml");
  }

  @Test
  public void testFailure_deletedDomain() throws Exception {
    thrown.expect(
        ResourceToMutateDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    domain = persistResource(
        domain.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    doFailingTest("domain_transfer_cancel.xml");
  }

  @Test
  public void testFailure_nonexistentDomain() throws Exception {
    thrown.expect(
        ResourceToMutateDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    deleteResource(domain);
    doFailingTest("domain_transfer_cancel.xml");
  }

  @Test
  public void testFailure_notAuthorizedForTld() throws Exception {
    thrown.expect(NotAuthorizedForTldException.class);
    persistResource(
        Registrar.loadByClientId("NewRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.<String>of())
            .build());
    doSuccessfulTest("domain_transfer_cancel.xml", "domain_transfer_cancel_response.xml");
  }

  // NB: No need to test pending delete status since pending transfers will get cancelled upon
  // entering pending delete phase. So it's already handled in that test case.
}