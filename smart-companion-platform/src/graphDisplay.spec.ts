import {expect,it} from 'vitest';
import {missingHierarchyEdges} from './graphDisplay';
it('uses published CONTAINS once and derives only missing parentId hierarchy without altering semantic edges',()=>{
 const nodes=[{id:'root',parentId:null},{id:'a',parentId:'root'},{id:'b',parentId:'root'}];
 const edges=[{sourceId:'root',targetId:'a',relation:'CONTAINS'},{sourceId:'a',targetId:'b',relation:'PREREQUISITE'}];
 expect(missingHierarchyEdges(nodes,edges)).toEqual([{sourceId:'root',targetId:'b',relation:'CONTAINS'}]);
 expect(edges).toHaveLength(2);
 expect(missingHierarchyEdges(nodes,[...edges,...missingHierarchyEdges(nodes,edges)])).toEqual([]);
});
