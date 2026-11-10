package com.lifesync.poc.campaign;

import com.lifesync.poc.product.CustomerSubgraph;
import java.util.List;

public final class CampaignModels {
    private CampaignModels() {}
    public record Run(String runId,String status,String createdAt,String finishedAt,
                      long audienceCount,long readyCount,long skippedCount,long failedCount) {}
    public record Preview(String previewId,String runId,String customerId,String status,
                          String subject,String introduction,String source,List<String> goalIds,
                          String generatedAt,String reason) {}
    public record PreviewPage(Run run,Preview preview,CustomerSubgraph graph) {}
}
