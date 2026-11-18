package com.lifesync.poc.conversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.product.CustomerSubgraph;
import com.lifesync.poc.product.CustomerProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ConversationPlannerTest {
    final ObjectMapper mapper=new ObjectMapper();
    @SuppressWarnings("unchecked") final ConversationPlanner planner=new ConversationPlanner(mock(ObjectProvider.class),mapper,true);
    final CustomerSubgraph goals=new CustomerSubgraph("C1","Person",List.of(new CustomerSubgraph.Goal("G1","Furniture","PLCC","PLCC",List.of(),"GOAL_ONLY",List.of())));
    @Test void followupAllowsNullableFieldsButRejectsForeignIdsAndCustomerArguments() throws Exception {
        var valid=mapper.readTree("""
            {"action":"MERCHANTS","goalIds":[],"reusePrevious":true,"maxDistanceMiles":5,
             "minDiscountPercent":null,"sort":null,"offersOnly":null,"limit":null,"explanation":""}
            """);
        var plan=planner.validate(valid,goals);assertNull(plan.sort());assertNull(plan.offersOnly());assertNull(plan.limit());
        var bad=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)bad).put("customerId","C2");
        assertThrows(IllegalArgumentException.class,()->planner.validate(bad,goals));
        var foreign=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)foreign).putArray("goalIds").add("FOREIGN");
        assertThrows(IllegalArgumentException.class,()->planner.validate(foreign,goals));
    }
    @Test void shoppingQuestionUsesTheModelActionWithoutReplacingIt() throws Exception {
        var models=org.mockito.Mockito.<ObjectProvider<ChatModel>>mock();
        var model=mock(ChatModel.class);
        when(models.getIfAvailable()).thenReturn(model);
        String response="""
            {"action":"%s","goalIds":["G1"],"reusePrevious":false,"maxDistanceMiles":null,
             "minDiscountPercent":null,"sort":null,"offersOnly":null,"limit":null,"explanation":""}
            """;
        when(model.call(any(Prompt.class))).thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage(response.formatted("MERCHANTS"))))),
            new ChatResponse(List.of(new Generation(new AssistantMessage(response.formatted("GOALS"))))));
        var planner=new ConversationPlanner(models,mapper,true);
        var state=new ConversationState(new CustomerProfile("C1","Alex","Walker","Seattle","WA","ACTIVE"));
        String question="Where can I buy furniture?";
        var modeled=planner.plan(state,goals,question);
        assertEquals("MERCHANTS",modeled.action());
        assertNull(modeled.sort());
        var goalPlan=planner.plan(state,goals,question);
        assertEquals("GOALS",goalPlan.action());
        assertEquals(List.of("G1"),goalPlan.goalIds());
        assertNull(goalPlan.sort());
        verify(model,times(2)).call(any(Prompt.class));
    }
    @Test void unsupportedActionsCannotWriteOrRunCypher() throws Exception {
        var json=mapper.readTree("""
            {"action":"SEND_EMAIL","goalIds":[],"reusePrevious":false,"sort":"DISTANCE","offersOnly":false,"limit":5,"explanation":""}
            """);
        assertThrows(IllegalArgumentException.class,()->planner.validate(json,goals));
    }
}
