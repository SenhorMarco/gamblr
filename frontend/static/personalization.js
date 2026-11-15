// Um unico renderer para nomes dentro do grupo; texto nunca vira HTML.
export function groupName(person,{tag='span',className='',suffix=''}={}) {
  const wrapper=document.createElement(tag);wrapper.className=`group-person ${className}`.trim();
  const name=document.createElement('span');name.textContent=person.displayName+suffix;
  if(/^#[0-9a-f]{6}$/i.test(person.nameColor||''))name.style.color=person.nameColor;
  wrapper.append(name);
  if(person.title){const title=document.createElement('span');title.className='group-title';title.textContent=person.title;if(/^#[0-9a-f]{6}$/i.test(person.titleColor||''))title.style.color=person.titleColor;wrapper.append(title);}
  return wrapper;
}

export function purchaseId() {
  const bytes=crypto.getRandomValues(new Uint8Array(16));bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;
  const hex=Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}

export function personalizationForms({state,displayName,owner,buy,configure,remove}) {
  const section=document.createElement('section');section.className='personalization';
  const node=(tag,text)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n;};
  section.append(node('h3','Personalização no grupo'),groupName({...state.style,displayName}),node('p','Cada alteração é uma compra independente. Remover o título não devolve pontos.'));
  const prices=state.prices;
  const pending=new Map();
  for(const [action,key,label] of [['NAME_COLOR','nameColor','Cor do nome'],['TITLE','title','Texto do título'],['TITLE_COLOR','titleColor','Cor do título']]) {
    const form=node('form'),field=node('label',label),input=node('input');input.name='value';input.required=true;
    if(action==='TITLE'){input.type='text';input.value=state.style.title||'';input.placeholder='Até 30 caracteres, sem emojis';}
    else{input.type='color';input.value=state.style[key]||'#333333';}
    field.append(input);form.append(field);
    const price=prices[key],button=node('button',price==null?'Preço não configurado':`Comprar alteração · ${String(price).replace('.',',')} pontos`);button.type='submit';button.className='button secondary';
    button.disabled=price==null||(action==='TITLE_COLOR'&&!state.style.title);form.append(button);
    if(action==='TITLE_COLOR'&&!state.style.title)form.append(node('small','Compre o texto do título primeiro.'));
    form.onsubmit=async event=>{
      event.preventDefault();button.disabled=true;
      const value=input.value;
      let request=pending.get(action);
      if(!request||request.value!==value)request={action,value,expectedPrice:price,clientId:purchaseId()};
      pending.set(action,request);
      try{if(await buy(request))pending.delete(action);}finally{button.disabled=false;}
    };
    section.append(form);
  }
  if(state.style.title){const button=node('button','Remover meu título sem reembolso');button.type='button';button.className='button secondary';button.onclick=async()=>{button.disabled=true;try{await remove();}finally{button.disabled=false;}};section.append(button);}
  if(owner) {
    const form=node('form');form.append(node('h3','Preços das personalizações'));
    for(const [key,label] of [['nameColor','Cor do nome'],['title','Texto do título'],['titleColor','Cor do título']]) {
      const field=node('label',label),input=node('input');input.name=key;input.type='number';input.min='0';input.step='any';input.required=true;input.value=prices[key]??'';field.append(input);form.append(field);
    }
    const button=node('button','Salvar preços');button.type='submit';button.className='button primary';form.append(button);
    form.onsubmit=async event=>{event.preventDefault();button.disabled=true;try{await configure(Object.fromEntries(new FormData(form)));}finally{button.disabled=false;}};section.append(form);
  }
  return section;
}
