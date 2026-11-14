export function createResolutions({api,onChanged}) {
  let current=null;
  const labels={IN_PROGRESS:'Aposta em andamento',AWAITING_RESULT:'Aguardando resultado do autor',CONTEST_PERIOD:'Prazo de contestação',AWAITING_OWNER:'Aguardando decisão do dono',AWAITING_SETTLEMENT:'Aguardando apuração',SETTLED:'Aposta concluída',CANCELLED:'Aposta cancelada'};
  const reasons={RESULT_TIMEOUT:'O autor não publicou o resultado no prazo.',AUTHOR_LEFT:'O autor saiu antes de publicar o resultado.',AUTHOR_CANCELLED:'Cancelada pelo autor nos primeiros 10% da duração.',OWNER_CANCELLED:'Cancelada pelo dono ao decidir a contestação.',NO_WINNERS:'Ninguém escolheu a opção vencedora. Não houve pagamento.',ZERO_WINNING_STAKE:'Os vencedores apostaram zero pontos. Os pontos perdidos não foram distribuídos.',PAID:'Os pontos foram apurados e os saldos atualizados.'};
  const node=(tag,cls,text)=>{const element=document.createElement(tag);if(cls)element.className=cls;if(text!==undefined)element.textContent=text;return element;};
  const date=value=>new Date(value).toLocaleString('pt-BR',{day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit'});
  const points=value=>Number(value).toLocaleString('pt-BR',{maximumFractionDigits:6});
  function error(scope,message) {if(current!==scope)return;scope.error.textContent=message;scope.error.hidden=false;}
  async function action(scope,path,body) {
    if(scope.busy)return;scope.busy=true;scope.error.hidden=true;
    scope.panel.querySelectorAll('button').forEach(button=>button.disabled=true);
    try {
      const data=await api('writer',`/api/bets/${scope.bet.id}/${path}`,{method:'POST',...(body===undefined?{}:{body})});
      if(current!==scope)return;scope.key='';render(scope,data);await onChanged(data,true);
    } catch(problem) {error(scope,problem.message);}
    finally{scope.busy=false;if(current===scope)scope.panel.querySelectorAll('button').forEach(button=>button.disabled=false);}
  }
  function options(scope,id,label) {
    const wrapper=node('label','',label),select=node('select');select.id=id;select.required=true;
    const placeholder=node('option','','Selecione uma opção');placeholder.value='';select.append(placeholder);
    for(const option of scope.bet.options){const item=node('option','',option.label);item.value=option.id;select.append(item);}
    wrapper.append(select);return {wrapper,select};
  }
  function button(label) {const result=node('button','button primary',label);result.type='submit';return result;}
  function render(scope,data) {
    if(current!==scope)return;
    scope.panel.replaceChildren(node('h3','resolution-title',labels[data.phase]||data.state));
    const name=id=>scope.bet.options.find(option=>option.id===id)?.label||'—';
    if(data.publishedOptionId)scope.panel.append(node('p','',`Resultado publicado: ${name(data.publishedOptionId)}`));
    if(data.finalOptionId&&data.finalOptionId!==data.publishedOptionId&&data.lastDecisionAction!=='CANCEL')scope.panel.append(node('p','',`Resultado final definido pelo dono: ${name(data.finalOptionId)}`));
    if(data.lastDecisionAction==='CANCEL'&&data.state==='RESULT_PUBLISHED')scope.panel.append(node('p','','Decisão do dono: cancelar e devolver os pontos após o prazo, sem contestações pendentes.'));
    if(data.lastDecisionAction&&data.state==='RESULT_PUBLISHED')scope.panel.append(node('p','resolution-note','A decisão do dono foi registrada. O prazo de contestação continua até o fim.'));
    if(data.state==='OPEN')scope.panel.append(node('p','resolution-note',`Prazo do autor para publicar o resultado: ${date(data.resultDeadline)}.`));
    if(data.contestDeadline&&data.state==='RESULT_PUBLISHED') {
      scope.panel.append(node('p','resolution-note',`Contestações até ${date(data.contestDeadline)}. Os pontos permanecem reservados até a conclusão.`),node('p','',`${data.pendingContests} contestação(ões) pendente(s).`));
      if(data.myPendingContest)scope.panel.append(node('p','form-info','Sua contestação está pendente de decisão do dono.'));
    }
    if(data.conclusionReason)scope.panel.append(node('p','resolution-note',reasons[data.conclusionReason]||data.conclusionReason));
    if(data.myMovement) {
      const text=data.myMovement==='REFUND'?`Devolução: ${points(data.myCredit)} pontos.`:data.myMovement==='LOSS'?'Seu palpite não venceu. Os pontos reservados foram descontados.':`Crédito recebido: ${points(data.myCredit)} pontos (inclui o valor apostado).`;
      scope.panel.append(node('p','resolution-credit',text));
    }
    if(data.canPublishResult) {
      const form=node('form');form.id='result-form';const choice=options(scope,'result-option','Opção vencedora');form.append(choice.wrapper,button('Publicar resultado'));
      form.onsubmit=event=>{event.preventDefault();action(scope,'result',{optionId:choice.select.value});};scope.panel.append(form);
    }
    if(data.canContest) {
      const contest=node('button','button secondary','Contestar resultado');contest.type='button';contest.id='contest-button';contest.onclick=()=>action(scope,'contests');scope.panel.append(contest);
    }
    if(data.canDecide) {
      const form=node('form');form.id='decision-form';const label=node('label','','Decisão do dono'),select=node('select');select.id='decision-action';
      for(const [value,text] of [['KEEP','Manter o resultado publicado'],['CHANGE','Escolher outra opção'],['CANCEL','Cancelar e devolver os pontos']]){const option=node('option','',text);option.value=value;select.append(option);}
      label.append(select);const choice=options(scope,'decision-option','Opção vencedora final');choice.wrapper.hidden=true;choice.select.required=false;
      select.onchange=()=>{choice.wrapper.hidden=select.value!=='CHANGE';choice.select.required=select.value==='CHANGE';};
      form.append(label,choice.wrapper,node('p','resolution-note','A decisão preserva o prazo de contestação. Pagamento ou devolução só ocorre após esse prazo, sem contestações pendentes.'),button('Confirmar decisão'));
      form.onsubmit=event=>{event.preventDefault();action(scope,'decision',{action:select.value,...(select.value==='CHANGE'?{optionId:choice.select.value}:{})});};scope.panel.append(form);
    }
    if(data.canCancel) {
      const cancel=node('button','button secondary','Cancelar aposta e devolver pontos');cancel.type='button';cancel.id='cancel-bet-button';cancel.onclick=()=>action(scope,'cancel');scope.panel.append(cancel);
    }
    scope.error=node('p','form-error');scope.error.role='alert';scope.error.hidden=true;scope.panel.append(scope.error);
  }
  async function refresh() {
    const scope=current;if(!scope||scope.loading||scope.busy)return;scope.loading=true;
    try {
      const data=await api('writer',`/api/bets/${scope.bet.id}/resolution`);if(current!==scope)return;
      const key=JSON.stringify(data);if(key!==scope.key){scope.key=key;render(scope,data);}await onChanged(data,false);
    } catch(problem) {error(scope,problem.message);}
    finally{scope.loading=false;}
  }
  return {
    attach(bet,container){const panel=node('section','resolution-panel');panel.id='bet-resolution';panel.setAttribute('aria-label','Resultado e contestação');const message=node('p','resolution-note','Carregando resultado e prazos…');panel.append(message);container.append(panel);current={bet,panel,error:message,key:'',loading:false,busy:false};refresh();},
    refresh,reset(){current=null;},
  };
}
