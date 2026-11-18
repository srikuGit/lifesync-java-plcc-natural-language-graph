package com.lifesync.poc.conversation;

import com.lifesync.poc.mcp.GraphMcpClient;
import com.lifesync.poc.product.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

/** Bounded Java dispatcher: at most one goal-list and one merchant MCP tool call per turn. */
@Service
public class ConversationService {
    private final GraphMcpClient tools;
    private final ConversationPlanner planner;
    public ConversationService(GraphMcpClient tools,ConversationPlanner planner){this.tools=tools;this.planner=planner;}
    public ConversationState start(String customer){
        if(customer==null||!customer.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("Enter a valid customer ID.");
        return new ConversationState(tools.customerProfile(customer));
    }
    public void refreshProfile(ConversationState state){state.updateProfile(tools.customerProfile(state.profile().customerId()));}
    public ConversationState.Reply turn(ConversationState state,String question){return turn(state,question,null);}
    public ConversationState.Reply turn(ConversationState state,String question,String action){
        if(action!=null&&!Set.of("GOALS","MERCHANTS","OFFERS").contains(action))throw new IllegalArgumentException("Unknown assistant action.");
        if(question==null||question.isBlank()||question.length()>500)throw new IllegalArgumentException("Enter a question of up to 500 characters.");
        if(!state.busy.compareAndSet(false,true))throw new IllegalStateException("Another reply is being prepared. Please wait.");
        int toolCalls=0;
        try {
            if(state.goals==null||System.currentTimeMillis()-state.retrievedAt>300_000){
                state.goals=tools.customerGoals(state.profile().customerId());toolCalls++;
                state.retrievedAt=System.currentTimeMillis();state.lastResult=null;state.previous=null;
            }
            if(state.lastResult!=null){refreshProfile(state);toolCalls++;}
            // Example buttons carry an explicit action, so shopping requests cannot be misclassified as lending.
            var plan=action==null?planner.plan(state,state.goals,question):new ConversationPlanner.Plan(action,
                state.goals.goals().stream().filter(g->action.equals("GOALS")||"PLCC".equals(g.productId()))
                    .map(CustomerSubgraph.Goal::goalId).toList(),false,null,null,"DISTANCE",action.equals("OFFERS"),null,"");
            if(plan.action().equals("HELP"))return save(state,question,new ConversationState.Reply(
                "I can list your goals, find nearby PLCC shops and compare available shopping discounts. Try ‘View Goals’ or ‘Show shops near me’.","ASSISTANT_HELP",null,false,false,toolCalls));
            if(plan.action().equals("EXPLAIN")) {
                if(state.lastResult==null)return save(state,question,new ConversationState.Reply("Ask for shops or discounts first, then I can explain the displayed matches.","GRAPH",null,false,false,toolCalls));
                if(restricted(state.lastResult))return save(state,question,new ConversationState.Reply("PLCC merchant details are not displayed for this account.","ACCOUNT_RESTRICTED",state.lastResult,true,true,toolCalls));
                String text=plan.explanation();
                if(text.length()<10||text.matches("(?s).*[0-9<>$%].*")||text.toLowerCase(Locale.ROOT).matches(".*\\b(guaranteed|approved|approval|eligible)\\b.*"))
                    text="These merchants match the categories of your PLCC shopping goals. Compare the displayed locations, discounts and offer conditions.";
                return save(state,question,new ConversationState.Reply(text,"GRAPH_GROUNDED_EXPLANATION",state.lastResult,true,restricted(state.lastResult),toolCalls));
            }
            // A named goal starts a fresh search; model-selected limits must not silently hide shops.
            boolean namedGoal=state.goals.goals().stream().anyMatch(g->normalized(question).contains(normalized(g.description())));
            boolean reusePrevious=plan.reusePrevious()&&!namedGoal;
            var selected=new ArrayList<>(plan.goalIds());
            if(reusePrevious&&state.previous!=null&&selected.isEmpty()) {
                selected.addAll(state.previous.goalIds());
                if(!plan.action().equals("GOALS")) selected.removeIf(id->state.goals.goals().stream().anyMatch(g->g.goalId().equals(id)&&!"PLCC".equals(g.productId())));
            }
            Set<String> valid=new HashSet<>();state.goals.goals().forEach(g->valid.add(g.goalId()));
            if(!valid.containsAll(selected))throw new IllegalArgumentException("INVALID_PLAN");
            if(plan.action().equals("GOALS")){
                var selectedIds=Set.copyOf(selected);
                var goals=state.goals.goals().stream().filter(g->selectedIds.contains(g.goalId()))
                    .map(g->new CustomerSubgraph.Goal(g.goalId(),g.description(),g.productId(),g.productName(),g.categories(),g.status(),List.of())).toList();
                var graph=new CustomerSubgraph(state.profile().customerId(),state.profile().displayName(),goals);
                state.previous=plan;state.lastResult=null;
                return save(state,question,new ConversationState.Reply(goals.isEmpty()?"No goals matched that request.":
                    "Here are your goals. Ask about nearby shops or discounts when you want PLCC merchant and offer details. Other product goals are shown without provider details.","MCP_GOALS",graph,false,false,toolCalls));
            }
            var selectedIds=Set.copyOf(selected);
            var plcc=state.goals.goals().stream().filter(g->"PLCC".equals(g.productId()))
                .filter(g->selectedIds.contains(g.goalId())).map(CustomerSubgraph.Goal::goalId).toList();
            if(plcc.isEmpty())return save(state,question,new ConversationState.Reply("I can show those goals, but merchant and offer details are available only for PLCC shopping goals.","PRODUCT_POLICY",null,false,false,toolCalls));
            // Reject non-PLCC selection, rather than accidentally broadening it to all PLCC goals.
            if(!selected.isEmpty()&&selected.stream().anyMatch(id->state.goals.goals().stream().anyMatch(g->g.goalId().equals(id)&&!"PLCC".equals(g.productId()))))
                return save(state,question,new ConversationState.Reply("I can list home lending and auto-loan goals, but cannot show merchants or offers for those products.","PRODUCT_POLICY",null,false,false,toolCalls));
            Double distance=plan.maxDistanceMiles(),discount=plan.minDiscountPercent();
            if(reusePrevious&&state.previous!=null){if(distance==null)distance=state.previous.maxDistanceMiles();if(discount==null)discount=state.previous.minDiscountPercent();}
            String sort=plan.sort()!=null?plan.sort():reusePrevious&&state.previous!=null&&state.previous.sort()!=null?state.previous.sort():"DISTANCE";
            boolean offersOnly=(plan.offersOnly()!=null?plan.offersOnly():reusePrevious&&state.previous!=null&&Boolean.TRUE.equals(state.previous.offersOnly()))||plan.action().equals("OFFERS");
            Integer requestedLimit=requestedShopLimit(question);
            int limit=requestedLimit!=null?requestedLimit:reusePrevious&&state.previous!=null&&state.previous.limit()!=null?state.previous.limit():5;
            Double toolDiscount=discount!=null?discount:offersOnly?0d:null;
            var found=tools.plccMerchants(state.profile().customerId(),plcc,distance,toolDiscount,5);toolCalls++;
            if(toolCalls>3)throw new IllegalStateException("Tool budget exceeded");
            List<CustomerSubgraph.Goal> results=new ArrayList<>();
            for(var goal:found.goals()){
                if(!"PLCC".equals(goal.productId())||!plcc.contains(goal.goalId()))throw new IllegalStateException("MCP scope mismatch");
                var providers=goal.providers().stream().filter(p->!offersOnly||!p.offers().isEmpty())
                    .sorted(("DISCOUNT".equals(sort)?Comparator.comparingDouble((CustomerSubgraph.Provider p)->-p.offers().stream()
                        .filter(o->o.discountPercent()!=null).mapToDouble(ProductOfferCatalog.Offer::discountPercent).max().orElse(-1)):
                        Comparator.comparingDouble(CustomerSubgraph.Provider::distanceMiles))
                        .thenComparingDouble(CustomerSubgraph.Provider::distanceMiles).thenComparing(CustomerSubgraph.Provider::storeId))
                    .limit(limit).toList();
                results.add(new CustomerSubgraph.Goal(goal.goalId(),goal.description(),goal.productId(),goal.productName(),goal.categories(),goal.status(),providers));
            }
            var graph=new CustomerSubgraph(state.profile().customerId(),state.profile().displayName(),List.copyOf(results));
            state.lastResult=graph;state.previous=new ConversationPlanner.Plan(plan.action(),plcc,false,distance,discount,sort,offersOnly,limit,"");
            boolean restricted=restricted(graph);int count=graph.goals().stream().mapToInt(g->g.providers().size()).sum();
            String message=restricted?"I found your PLCC shopping goals. Merchant details are not displayed for this account.":
                graph.goals().stream().anyMatch(g->"NO_CUSTOMER_LOCATION".equals(g.status()))?"Your address has not been located yet. Nearby merchant results need a successful address lookup.":
                count==0?(distance!=null?"No eligible PLCC shops matched the requested distance and any other filters. Try a wider search.":
                    "No located PLCC merchants matched those filters. Try a wider distance or a lower minimum discount."):
                "Here are the matching PLCC shops"+(offersOnly?" with available offers":"")+". Distances are approximate straight-line miles; offer conditions still apply.";
            return save(state,question,new ConversationState.Reply(message,"MCP_PLCC_RESULTS",graph,true,restricted,toolCalls));
        } catch(Exception ex){
            String code="ASSISTANT_UNAVAILABLE";Throwable root=ex;
            for(int i=0;i<10&&root.getCause()!=null;i++)root=root.getCause();
            if(root instanceof TimeoutException)code="MODEL_TIMEOUT";
            else if(ex instanceof RejectedExecutionException)code="MODEL_BUSY";
            else if(ex instanceof IllegalArgumentException||ex instanceof com.fasterxml.jackson.core.JsonProcessingException)code="INVALID_PLAN";
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Conversation fallback: {}",code);
            return save(state,question,new ConversationState.Reply("I couldn't complete that request. Please retry or use one of the example questions. Your customer selection has not changed. ["+code+"]","GRAPH_FALLBACK",null,false,false,toolCalls));
        } finally {state.busy.set(false);}
    }
    private static String normalized(String text){return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").strip();}
    private static Integer requestedShopLimit(String question){
        var match=java.util.regex.Pattern.compile("(?i)\\b(one|two|three|four|five|[1-5])\\s+(?:(?:nearest|closest|best|matching)\\s+)?(?:shops?|stores?|merchants?|providers?|results?)\\b").matcher(question);
        if(!match.find())return null;
        return switch(match.group(1).toLowerCase(Locale.ROOT)){
            case "one"->1;case "two"->2;case "three"->3;case "four"->4;case "five"->5;default->Integer.valueOf(match.group(1));};
    }
    private boolean restricted(CustomerSubgraph g){return g.goals().stream().anyMatch(goal->"PLCC_ACCOUNT_RESTRICTED".equals(goal.status()));}
    private ConversationState.Reply save(ConversationState s,String q,ConversationState.Reply reply){s.add(q,reply);return reply;}
}
