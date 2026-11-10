package com.lifesync.poc.campaign;

import com.lifesync.poc.mcp.GraphMcpClient;
import com.lifesync.poc.product.*;
import org.junit.jupiter.api.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static com.lifesync.poc.campaign.CampaignModels.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ManualCampaignServiceTest {
    final CampaignRepository repository=mock(CampaignRepository.class);
    final GraphMcpClient mcp=mock(GraphMcpClient.class);
    final CampaignPreviewComposer composer=mock(CampaignPreviewComposer.class);
    final ManualCampaignService service=new ManualCampaignService(repository,mcp,composer);
    final ProductOfferCatalog.Offer offer=new ProductOfferCatalog.Offer("O1","PLCC","M1",null,"Furniture discount","Terms","ACTIVE",LocalDate.of(2026,1,1),LocalDate.of(2030,1,1),15.,null,null,null);
    CustomerSubgraph graph() {
        return new CustomerSubgraph("C001","Private Customer",List.of(new CustomerSubgraph.Goal("G1","Furnish my office","PLCC","Shopping",List.of("HOME_FURNISHINGS"),"MATCHED",
            List.of(new CustomerSubgraph.Provider("M1","S1","Merchant","Seattle","WA",1.2,List.of(offer))))));
    }
    @AfterEach void close(){service.close();}
    @Test void onlyActiveHoldersAreProcessedAndNoModelRunsForIneligibleCustomer() {
        when(repository.isActive("C001")).thenReturn(false);
        service.processCustomer("R1","C001");
        verify(repository).mark(eq("R1"),eq("C001"),eq("SKIPPED_INELIGIBLE"),anyString());
        verifyNoInteractions(mcp,composer);
    }
    @Test void readyPreviewStoresCopyAndGoalIdsButNoOfferSnapshot() {
        when(repository.isActive("C001")).thenReturn(true);
        when(mcp.customerGraph("C001","PLCC")).thenReturn(graph());
        when(composer.compose(any())).thenReturn(new CampaignPreviewComposer.Copy("Subject","Introduction","FACTUAL_TEMPLATE"));
        service.processCustomer("R1","C001");
        verify(repository).ready("R1","C001","Subject","Introduction","FACTUAL_TEMPLATE",List.of("G1"));
        verify(mcp).customerGraph("C001","PLCC");
    }
    @Test void accountIsCheckedAgainAfterGeneration() {
        when(repository.isActive("C001")).thenReturn(true,false);
        when(mcp.customerGraph("C001","PLCC")).thenReturn(graph());
        when(composer.compose(any())).thenReturn(new CampaignPreviewComposer.Copy("Subject","Introduction","FACTUAL_TEMPLATE"));
        service.processCustomer("R1","C001");
        verify(repository,never()).ready(anyString(),anyString(),anyString(),anyString(),anyString(),anyList());
        verify(repository).mark(eq("R1"),eq("C001"),eq("SKIPPED_INELIGIBLE"),contains("changed"));
    }
    @Test void missingOffersSkipGenerationAndForeignCustomerGraphFailsClosed() {
        when(repository.isActive("C001")).thenReturn(true);
        var original=graph().goals().getFirst();
        var noOffers=new CustomerSubgraph("C001","Customer",List.of(new CustomerSubgraph.Goal("G1",original.description(),"PLCC","Shopping",original.categories(),"MATCHED",List.of())));
        when(mcp.customerGraph("C001","PLCC")).thenReturn(noOffers);
        service.processCustomer("R1","C001");
        verify(repository).mark(eq("R1"),eq("C001"),eq("SKIPPED_NO_OFFERS"),anyString());
        verifyNoInteractions(composer);
        when(mcp.customerGraph("C001","PLCC")).thenReturn(new CustomerSubgraph("C999","Foreign",List.of()));
        service.processCustomer("R2","C001");
        verify(repository).mark(eq("R2"),eq("C001"),eq("FAILED"),anyString());
    }
    @Test void openingPreviewRechecksAccountAndNeverRegeneratesLlmCopy() {
        var run=new Run("R1","COMPLETED","now","now",1,1,0,0);
        var saved=new Preview("R1:C001","R1","C001","READY","Subject","Saved introduction","FACTUAL_TEMPLATE",List.of("G1"),"now","");
        when(repository.run("R1")).thenReturn(Optional.of(run));when(repository.preview("R1","C001")).thenReturn(Optional.of(saved));
        when(repository.isActive("C001")).thenReturn(false);
        assertThrows(IllegalStateException.class,()->service.preview("R1","C001"));verifyNoInteractions(mcp,composer);
        when(repository.isActive("C001")).thenReturn(true);when(mcp.customerGraph("C001","PLCC")).thenReturn(graph());
        var page=service.preview("R1","C001");
        assertEquals("Saved introduction",page.preview().introduction());assertEquals(offer,page.graph().goals().getFirst().providers().getFirst().offers().getFirst());
        verifyNoInteractions(composer);
    }
    @Test void manualStartReturnsWhileWorkerRunsAndRejectsConcurrentRun() throws Exception {
        when(repository.activeAudience(1)).thenReturn(List.of("C001"));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);
        when(repository.isActive("C001")).thenAnswer(call->{entered.countDown();release.await(3,TimeUnit.SECONDS);return false;});
        doAnswer(call->{finished.countDown();return null;}).when(repository).finish(anyString());
        String run=service.start(1);assertFalse(run.isBlank());assertTrue(entered.await(2,TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class,()->service.start(1));
        release.countDown();assertTrue(finished.await(2,TimeUnit.SECONDS));
        verify(repository).createRun(run,List.of("C001"));
        assertThrows(IllegalArgumentException.class,()->service.start(101));
    }
    @Test void workerFinishFailurePersistsTerminalStatusWithoutEscaping() {
        doThrow(new IllegalStateException("persistence unavailable")).when(repository).finish("R1");
        assertDoesNotThrow(()->service.process("R1",List.of()));
        verify(repository).failRun(eq("R1"),contains("execution stopped"));
        assertFalse(service.busy());
    }
    @Test void failureWhileRecordingCustomerStatusStopsRunAndAttemptsTerminalStatus() {
        when(repository.isActive("C001")).thenReturn(false);
        doThrow(new IllegalStateException("persistence unavailable")).when(repository).mark(anyString(),anyString(),anyString(),anyString());
        assertDoesNotThrow(()->service.process("R1",List.of("C001")));
        verify(repository).failRun(eq("R1"),anyString());
        verify(repository,never()).finish(anyString());
        assertFalse(service.busy());verifyNoInteractions(mcp,composer);
    }
    @Test void unavailableFailurePersistenceStillReleasesWorkerGate() {
        doThrow(new IllegalStateException("unavailable")).when(repository).finish("R1");
        doThrow(new IllegalStateException("still unavailable")).when(repository).failRun(eq("R1"),anyString());
        assertDoesNotThrow(()->service.process("R1",List.of()));
        assertFalse(service.busy());
    }

}
