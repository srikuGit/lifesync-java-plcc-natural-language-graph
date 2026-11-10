package com.lifesync.poc.conversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifesync.poc.product.CustomerSubgraph;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ChatModel;
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
    @Test void unsupportedActionsCannotWriteOrRunCypher() throws Exception {
        var json=mapper.readTree("""
            {"action":"SEND_EMAIL","goalIds":[],"reusePrevious":false,"sort":"DISTANCE","offersOnly":false,"limit":5,"explanation":""}
            """);
        assertThrows(IllegalArgumentException.class,()->planner.validate(json,goals));
    }
}
