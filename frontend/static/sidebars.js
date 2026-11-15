// Move os mesmos nos: conserva eventos, foco, conteudo e estado das conversas.
export function initializeSidebars() {
  const contacts=document.getElementById('contacts-column');
  if(!contacts)return;
  const nodes=[document.querySelector('.friends-heading'),...['friends-list','friends-error','private-nav'].map(id=>document.getElementById(id))];
  const slots=nodes.map(node=>{const marker=document.createComment('mobile-contacts-position');node.before(marker);return {node,marker};});
  const wide=window.matchMedia('(min-width: 821px)');
  function arrange(){
    const focused=document.activeElement,restoreFocus=slots.some(({node})=>node.contains(focused));
    // Nao recoloca nos em um resize que permaneceu na mesma faixa.
    if(wide.matches){for(const {node} of slots)contacts.append(node);contacts.hidden=false;}
    else{for(const {node,marker} of slots)marker.after(node);contacts.hidden=true;}
    if(restoreFocus&&document.activeElement!==focused)focused.focus({preventScroll:true});
  }
  wide.addEventListener('change',arrange);arrange();
}
