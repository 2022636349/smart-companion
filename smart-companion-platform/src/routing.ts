export type Role = 'home'|'teacher'|'student';
export const teacherPages=['overview','students','structure','plans','data'];
export const studentPages=['learning','structure','assistant','tasks'];
export function readRoute(hash=window.location.hash){
 const [path,query='']=hash.replace(/^#/, '').split('?');
 const [roleValue,pageValue]=(path||'/').split('/').filter(Boolean);
 const role:Role=roleValue==='teacher'?'teacher':roleValue==='student'?'student':'home';
 const pages=role==='teacher'?teacherPages:studentPages;
 const params=new URLSearchParams(query);
 return {role,page:pages.includes(pageValue)?pageValue:pages[0],studentId:params.get('student')||'',nodeId:params.get('scope')||''};
}
export function navigate(role:Role,page?:string,studentId='',nodeId=''){
 const params=new URLSearchParams();if(studentId)params.set('student',studentId);if(nodeId)params.set('scope',nodeId);
 const next=role==='home'?'#/':`#/${role}/${page||(role==='teacher'?'overview':'learning')}${params.size?'?'+params:''}`;
 if(window.location.hash!==next)window.location.hash=next;
}
