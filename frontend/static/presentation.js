const node=(tag,cls,text)=>{const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;};
export const accepting=bet=>bet.state==='OPEN'&&new Date(bet.predictionsCloseAt).getTime()>Date.now();
export function betSituation(bet,now=Date.now()) {
  if(bet.state==='SETTLED')return {key:'settled',label:'Concluída'};
  if(bet.state==='CANCELLED')return {key:'cancelled',label:'Cancelada'};
  if(bet.state==='RESULT_PUBLISHED')return {key:'published',label:'Resultado publicado'};
  if(new Date(bet.predictionsCloseAt).getTime()>now)return {key:'open',label:'Palpites abertos'};
  if(new Date(bet.endsAt).getTime()>now)return {key:'closed',label:'Palpites fechados'};
  return {key:'awaiting',label:'Aguardando resultado'};
}
export function statusTag(bet) {const status=betSituation(bet);return node('span',`tag bet-status status-${status.key}`,status.label);}

export function participationModel(data) {
  if(!data||!Number.isSafeInteger(data.totalVotes)||data.totalVotes<0||!Array.isArray(data.options)||!data.options.length)throw new Error('Participação inválida');
  const ids=new Set();let sum=0;
  const options=data.options.map(option=>{
    if(!option.optionId||ids.has(option.optionId)||typeof option.label!=='string'||!Number.isSafeInteger(option.votes)||option.votes<0)throw new Error('Participação inválida');
    ids.add(option.optionId);sum+=option.votes;
    return {...option,share:data.totalVotes?option.votes/data.totalVotes:0};
  });
  if(sum!==data.totalVotes)throw new Error('Participação inválida');
  return {totalVotes:data.totalVotes,options};
}
export function createParticipation({api,onUpdate}) {
  const records=new Map(),pending=new Map();let epoch=0;
  return {
    get(group,bet){const record=records.get(group);return record?.status==='ready'?(record.bets.get(bet)||{status:'error'}):record||{status:'loading'};},
    async load(group){
      if(pending.has(group))return pending.get(group);
      const session=epoch;
      if(!records.has(group))records.set(group,{status:'loading'});
      const operation=(async()=>{
        try {
          const data=await api('reader',`/api/groups/${group}/participation`);
          if(data.groupId!==group||!Array.isArray(data.bets))throw new Error('Resposta inválida');
          const bets=new Map(data.bets.map(bet=>[bet.betId,{status:'ready',data:participationModel(bet)}]));
          if(session===epoch)records.set(group,{status:'ready',bets});
        }catch{if(session===epoch)records.set(group,{status:'error'});}
        finally{if(session===epoch){pending.delete(group);onUpdate(group);}}
      })();
      pending.set(group,operation);return operation;
    },
    reset(){epoch++;records.clear();pending.clear();},
  };
}
export function participationView(bet,record) {
  const wrapper=node('div','card-participation');
  if(record.status!=='ready') {
    wrapper.append(node('p','participation-message',record.status==='error'?'Participação indisponível. Tente atualizar os dados.':'Carregando participação…'));
    return wrapper;
  }
  const {totalVotes,options}=record.data;
  const heading=node('div','participation-heading');heading.append(node('span','',totalVotes?`${totalVotes} palpite${totalVotes===1?'':'s'}`:'Sem palpites ainda'),node('span','',accepting(bet)?'Acompanhando palpites':'Participação encerrada'));wrapper.append(heading);
  const bar=node('div','participation-bar');bar.setAttribute('role','img');
  const descriptions=[];
  const legend=node('div','participation-legend');
  options.forEach((option,index)=>{
    const percentage=(option.share*100).toLocaleString('pt-BR',{maximumFractionDigits:1});
    const text=`${option.label}: ${option.votes} palpite${option.votes===1?'':'s'}${totalVotes?` · ${percentage}%`:''}`;
    descriptions.push(text);
    if(totalVotes){const segment=node('span',`participation-segment option-color-${index%8}`);segment.style.flexGrow=String(option.share);segment.title=text;segment.setAttribute('aria-hidden','true');bar.append(segment);}
    const item=node('span','participation-option'),swatch=node('i',`participation-swatch option-color-${index%8}`);swatch.setAttribute('aria-hidden','true');item.append(swatch,document.createTextNode(text));legend.append(item);
  });
  bar.setAttribute('aria-label',totalVotes?descriptions.join('; '):'Sem palpites. Distribuição ainda não formada.');wrapper.append(bar,legend);
  return wrapper;
}

function decimalDifference(above,mine) {
  const parts=value=>{const match=String(value).match(/^(\d+)(?:\.(\d+))?$/);if(!match)throw new Error('Saldo inválido');return [match[1],match[2]||''];};
  const a=parts(above),b=parts(mine),scale=Math.max(a[1].length,b[1].length);
  const difference=BigInt(a[0]+a[1].padEnd(scale,'0'))-BigInt(b[0]+b[1].padEnd(scale,'0'));
  if(difference<0n)throw new Error('Ranking inválido');
  const digits=difference.toString().padStart(scale+1,'0');
  const whole=scale?digits.slice(0,-scale):digits,fraction=scale?digits.slice(-scale).replace(/0+$/,''):'';
  return {zero:difference===0n,text:BigInt(whole).toLocaleString('pt-BR')+(fraction?','+fraction:'')};
}
export function rankingMessage(rows,userId) {
  const mine=rows.find(row=>row.userId===userId);if(!mine)return 'Sua posição ainda não está disponível.';
  if(Number(mine.position)===1)return 'Você está em 1º lugar.';
  const above=rows.find(row=>Number(row.position)===Number(mine.position)-1);
  if(!above)return 'Sua posição ainda não está disponível.';
  try {
    const gap=decimalDifference(above.availablePoints,mine.availablePoints);
    return gap.zero?`Você está em ${mine.position}º lugar, empatado em pontos com o integrante acima.`:`Você está em ${mine.position}º lugar, a ${gap.text} pontos do próximo colocado.`;
  }catch{return 'Sua posição ainda não está disponível.';}
}

const drawings={
  bets:'M13 7h22v34H13z M19 17h10 M19 24h10 M19 31h6 M30 5l7 7',
  groups:'M18 20a6 6 0 1 0 0-12 6 6 0 0 0 0 12 M30 22a5 5 0 1 0 0-10 M6 40v-6c0-10 24-10 24 0v6 M34 28c6 0 8 4 8 10',
  chat:'M6 9h36v25H23L13 42v-8H6z M14 18h20 M14 25h14',
  ranking:'M6 42V26h12v16 M18 42V14h12v28 M30 42V32h12v10 M21 6h6',
  search:'M32 32l10 10 M35 21a14 14 0 1 0-28 0 14 14 0 0 0 28 0',
};
export function emptyState({kind='bets',title,description,actions=[]}) {
  const wrapper=node('div','empty-state illustrated-empty');
  const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 48 48');svg.setAttribute('aria-hidden','true');svg.classList.add('empty-illustration');
  const path=document.createElementNS(svg.namespaceURI,'path');path.setAttribute('d',drawings[kind]||drawings.bets);svg.append(path);
  wrapper.append(svg,node('strong','empty-title',title),node('span','empty-description',description));
  if(actions.length){const buttons=node('div','empty-actions');for(const {label,run} of actions){const button=node('button','button secondary',label);button.type='button';button.onclick=async()=>{button.disabled=true;try{await run();}catch(error){let message=wrapper.querySelector('.form-error');if(!message){message=node('p','form-error');message.setAttribute('role','alert');wrapper.append(message);}message.textContent=error.message||'Não foi possível concluir a ação.';}finally{button.disabled=false;}};buttons.append(button);}wrapper.append(buttons);}
  return wrapper;
}
