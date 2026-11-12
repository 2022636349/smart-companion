package com.education.agent.integration.knowledge;

import com.education.agent.common.error.BusinessException;
import com.education.agent.common.error.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ApprovedGraphContextValidator {
  private ApprovedGraphContextValidator() {}

  public static InternalGraphRagContextResponse require(String requestedId, InternalGraphRagContextResponse response) {
    InternalGraphNode node=response==null?null:response.knowledgePoint();
    if(node==null||requestedId==null||!requestedId.equals(node.id())||node.name()==null||node.name().isBlank()||node.name().length()>200
        ||!"knowledge_point".equals(node.nodeType())||!"APPROVED".equals(node.reviewStatus())
        ||node.contentVersion()==null||node.contentVersion()<1||response.graphVersion()==null
        ||response.graphVersion().isBlank()||"unknown".equalsIgnoreCase(response.graphVersion())
        ||!"READY".equals(response.contextStatus()))
      throw new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"知识图谱返回了未审核或版本无效的上下文");
    return response;
  }

  public static Set<String> allowedContextNodeIds(String requestedId, InternalGraphRagContextResponse response) {
    require(requestedId,response);
    if(response.prerequisites()==null||response.successors()==null||response.relatedNodes()==null)
      throw invalid();
    LinkedHashSet<String> ids=new LinkedHashSet<>();
    ids.add(response.knowledgePoint().id());
    addApproved(ids,response.prerequisites());
    addApproved(ids,response.successors());
    addApproved(ids,response.relatedNodes());
    return Set.copyOf(ids);
  }

  private static void addApproved(Set<String> ids,List<InternalGraphNode> nodes){
    for(InternalGraphNode node:nodes){
      if(node==null||node.id()==null||node.id().isBlank()||node.id().length()>64
          ||node.name()==null||node.name().isBlank()||node.name().length()>200
          ||!"knowledge_point".equals(node.nodeType())
          ||!"APPROVED".equals(node.reviewStatus())||node.contentVersion()==null||node.contentVersion()<1
          )throw invalid();
      ids.add(node.id());
    }
  }

  private static BusinessException invalid(){return new BusinessException(ErrorCode.GRAPH_SERVICE_UNAVAILABLE,"知识图谱返回了无效的已审核上下文");}
}
