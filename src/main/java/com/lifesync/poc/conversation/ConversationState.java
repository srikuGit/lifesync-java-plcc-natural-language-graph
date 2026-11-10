package com.lifesync.poc.conversation;

import com.lifesync.poc.product.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Session-only memory, isolated by customer and bounded to six exchanges. */
public final class ConversationState {
    private volatile CustomerProfile profile;
    private final String id=UUID.randomUUID().toString();
    private final Deque<Message> history=new ArrayDeque<>();
    final AtomicBoolean busy=new AtomicBoolean();
    CustomerSubgraph goals;
    CustomerSubgraph lastResult;
    ConversationPlanner.Plan previous;
    long retrievedAt;
    public record Reply(String message,String source,CustomerSubgraph graph,boolean showProviders,
                        boolean showConsent,int toolCalls) {}
    public record Message(String role,String text,Reply reply) {}
    public ConversationState(CustomerProfile profile){this.profile=profile;}
    public CustomerProfile profile(){return profile;}
    public String id(){return id;}
    public boolean busyForRequest(){return busy.get();}
    public synchronized void updateProfile(CustomerProfile current){
        if(!profile.customerId().equals(current.customerId()))throw new IllegalArgumentException("Customer scope mismatch");
        profile=current;
        if(!current.plccHolder()) {
            var safe=new ArrayDeque<Message>();
            for(var m:history){
                if(m.reply()!=null&&m.reply().showProviders()&&m.reply().graph()!=null&&m.reply().graph().goals().stream().anyMatch(g->!g.providers().isEmpty())){
                    var reply=new Reply("Previously displayed PLCC merchant details are no longer available for this account.","ACCOUNT_RESTRICTED",restricted(m.reply().graph()),true,true,0);
                    safe.addLast(new Message("assistant",reply.message(),reply));
                } else safe.addLast(m);
            }
            history.clear();history.addAll(safe);lastResult=restricted(lastResult);
        }
    }
    private CustomerSubgraph restricted(CustomerSubgraph g){
        if(g==null)return null;
        return new CustomerSubgraph(g.customerId(),g.displayName(),g.goals().stream().map(goal->new CustomerSubgraph.Goal(
            goal.goalId(),goal.description(),goal.productId(),goal.productName(),goal.categories(),"PLCC_ACCOUNT_RESTRICTED",List.of())).toList());
    }
    public boolean resetIfIdle(){if(!busy.compareAndSet(false,true))return false;try{clear();return true;}finally{busy.set(false);}}
    public synchronized List<Message> messages(){return List.copyOf(history);}
    public synchronized void add(String question,Reply reply){
        history.addLast(new Message("user",question,null));
        history.addLast(new Message("assistant",reply.message(),reply));
        while(history.size()>12)history.removeFirst();
    }
    public synchronized Reply lastReply(){return history.isEmpty()?null:history.getLast().reply();}
    public synchronized void clear(){history.clear();goals=null;lastResult=null;previous=null;retrievedAt=0;}
}
