interface HierarchyNode {id:string;parentId:string|null}
interface GraphEdge {sourceId:string;targetId:string;relation:string}
/** Derive display-only hierarchy edges only when the API has not supplied them. */
export function missingHierarchyEdges(nodes:HierarchyNode[],edges:GraphEdge[]):GraphEdge[]{
  return nodes.filter(n=>n.parentId&&nodes.some(p=>p.id===n.parentId)&&!edges.some(e=>e.sourceId===n.parentId&&e.targetId===n.id&&e.relation==='CONTAINS')).map(n=>({sourceId:n.parentId!,targetId:n.id,relation:'CONTAINS'}));
}
