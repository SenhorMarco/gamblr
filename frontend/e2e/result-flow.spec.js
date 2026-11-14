import {test,expect} from '@playwright/test';
const uid=n=>`10000000-0000-0000-0000-${String(n).padStart(12,'0')}`;
test('author publishes, participant contests, owner decides and payout is displayed',async({browser},testInfo)=>{
  const contexts=[],errors=[];let state='OPEN',published=null,final=null,pending=false,decision=null,expired=false;
  const bet={id:uid(4),groupId:uid(3),authorId:uid(1),question:'O time venceu?',type:'BINARY',minimumPoints:10,publishedAt:new Date(Date.now()-7200000).toISOString(),endsAt:new Date(Date.now()-3600000).toISOString(),predictionsCloseAt:new Date(Date.now()-4000000).toISOString(),authorCancelUntil:new Date(Date.now()-6800000).toISOString(),options:[{id:uid(5),label:'Sim',position:0},{id:uid(6),label:'Não',position:1}]};
  const group={id:uid(3),ownerId:uid(1),name:'Grupo dos resultados',member:true,memberCount:2};
  const prediction={id:uid(7),optionId:uid(5),points:30};
  async function pageFor(role) {
    const context=await browser.newContext();contexts.push(context);
    await context.addInitScript(session=>sessionStorage.setItem('gamblr.session',JSON.stringify(session)),{access_token:role,refresh_token:role,expires_at:Math.floor(Date.now()/1000)+3600,user:{email:`${role}@example.test`,user_metadata:{display_name:role==='owner'?'Marco':'Ana'}}});
    const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
    await page.route('**/status',route=>route.fulfill({json:{writer:true,reader:true}}));
    await page.route('**/reader/api/**',route=>route.fulfill({json:new URL(route.request().url()).pathname.endsWith('/ranking')?{ranking:[{position:1,userId:uid(1),displayName:'Marco',availablePoints:100},{position:2,userId:uid(2),displayName:'Ana',availablePoints:state==='SETTLED'?120:70}]}:{options:[{optionId:uid(5),votes:1,decimalOdds:'2.00'},{optionId:uid(6),votes:1,decimalOdds:'2.00'}]}}));
    await page.route('**/writer/api/**',async route=>{
      const request=route.request(),path=new URL(request.url()).pathname;let json={};
      if(path.endsWith('/result')){published=request.postDataJSON().optionId;final=published;state='RESULT_PUBLISHED';}
      if(path.endsWith('/contests'))pending=true;
      if(path.endsWith('/decision')){const body=request.postDataJSON();decision=body.action;pending=false;final=body.action==='KEEP'?published:body.optionId;}
      if(path.endsWith('/resolution')||path.endsWith('/result')||path.endsWith('/contests')||path.endsWith('/decision')) {
        if(expired&&!pending&&state==='RESULT_PUBLISHED')state='SETTLED';
        json={state,phase:state==='OPEN'?'AWAITING_RESULT':state==='SETTLED'?'SETTLED':'CONTEST_PERIOD',resultDeadline:new Date(Date.now()+3600000).toISOString(),contestDeadline:published?new Date(Date.now()+60000).toISOString():null,publishedOptionId:published,finalOptionId:final,concludedAt:state==='SETTLED'?new Date().toISOString():null,conclusionReason:state==='SETTLED'?'PAID':null,pendingContests:pending?1:0,myPendingContest:role!=='owner'&&pending,canPublishResult:role==='owner'&&state==='OPEN',canContest:role!=='owner'&&state==='RESULT_PUBLISHED'&&!pending&&!expired,canDecide:role==='owner'&&state==='RESULT_PUBLISHED'&&pending,canCancel:false,myCredit:role!=='owner'&&state==='SETTLED'?50:null,myMovement:role!=='owner'&&state==='SETTLED'?'PAYOUT':null,lastDecisionAction:decision};
      }else if(path.endsWith('/api/me'))json={id:role==='owner'?uid(1):uid(2),displayName:role==='owner'?'Marco':'Ana'};
      else if(path.endsWith('/api/friendships'))json=[];
      else if(path.endsWith('/api/groups')||path.endsWith('/api/groups/public'))json=[group];
      else if(path.endsWith('/bets'))json=[{bet:{...bet,state},myPrediction:role==='owner'?null:prediction}];
      else if(path.endsWith('/wallet/me'))json={availablePoints:role==='owner'?100:state==='SETTLED'?120:70,reservedPoints:role==='owner'||state==='SETTLED'?0:30};
      else if(path.endsWith('/messages'))json={messages:[],nextBefore:null,canModerate:role==='owner'};
      else return route.fulfill({status:404,json:{code:'NOT_FOUND',message:'Rota não simulada.'}});
      await route.fulfill({json});
    });
    await page.goto('/');return page;
  }
  try {
    const owner=await pageFor('owner'),participant=await pageFor('participant');
    await expect(owner.locator('#result-form')).toBeVisible();await expect(participant.locator('#result-form')).toHaveCount(0);
    await owner.locator('#result-option').selectOption(uid(5));await owner.locator('#result-form button').click();
    await participant.locator('#refresh-button').click();await expect(participant.locator('#bet-resolution')).toContainText('Resultado publicado: Sim');await participant.locator('#contest-button').click();await expect(participant.locator('#bet-resolution')).toContainText('Sua contestação está pendente');
    await owner.locator('#refresh-button').click();await expect(owner.locator('#decision-form')).toBeVisible();await expect(participant.locator('#decision-form')).toHaveCount(0);
    await owner.locator('#decision-action').selectOption('CANCEL');await owner.locator('#decision-form button').click();await expect(owner.locator('#bet-resolution')).toContainText('Decisão do dono: cancelar e devolver');await participant.locator('#refresh-button').click();await expect(participant.locator('#reserved-points')).toHaveText('30');await expect(participant.locator('#contest-button')).toBeVisible();await participant.locator('#contest-button').click();await owner.locator('#refresh-button').click();await expect(owner.locator('#decision-form')).toBeVisible();
    await owner.locator('#decision-action').selectOption('CHANGE');await owner.locator('#decision-option').selectOption(uid(6));await owner.locator('#decision-form button').click();await expect(owner.locator('#bet-resolution')).toContainText('Resultado final definido pelo dono: Não');await expect(owner.locator('#bet-resolution')).toContainText('O prazo de contestação continua');
    await participant.locator('#refresh-button').click();await expect(participant.locator('#contest-button')).toBeVisible();await participant.locator('#contest-button').click();await owner.locator('#refresh-button').click();await expect(owner.locator('#decision-form')).toBeVisible();await owner.locator('#decision-form button').click();
    expired=true;await participant.locator('#refresh-button').click();await expect(participant.locator('#bet-resolution')).toContainText('Aposta concluída');await expect(participant.locator('#bet-resolution')).toContainText('Crédito recebido: 50 pontos');await expect(participant.locator('#available-points')).toHaveText('120');await expect(participant.locator('#reserved-points')).toHaveText('0');await expect(participant.locator('.confirmed-box')).toContainText('A reserva foi concluída');await expect(participant.locator('#result-form')).toHaveCount(0);await expect(participant.locator('#contest-button')).toHaveCount(0);
    await participant.screenshot({path:testInfo.outputPath('result-settled-desktop.png'),fullPage:true});await participant.setViewportSize({width:390,height:844});await participant.screenshot({path:testInfo.outputPath('result-settled-mobile.png'),fullPage:true});expect(await participant.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();expect(errors).toEqual([]);
  }finally {for(const context of contexts)await context.close();}
});
