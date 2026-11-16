import {test,expect} from '@playwright/test';
const uid=n=>`40000000-0000-0000-0000-${String(n).padStart(12,'0')}`;

async function workspace(page,{empty=false,fail=false}={}) {
  const now=Date.now(),profile={id:uid(4),displayName:'Ana'},group={id:uid(10),ownerId:empty?profile.id:uid(1),name:'Turma de domingo',member:true,memberCount:empty?1:4};
  const f={votes:[3,1],failParticipation:fail,failRanking:fail,failMessages:fail,failBets:false,errors:[]};
  f.ranking=(empty?[{userId:profile.id,displayName:'Ana',position:1,availablePoints:'85'}]:['130','115','100','85'].map((points,index)=>({userId:uid(index+1),displayName:['Marco','Bia','Caio','Ana'][index],position:index+1,availablePoints:points}))).map(row=>({...row,...(row.userId===profile.id?{nameColor:'#123456',title:'Campeã',titleColor:'#abcdef'}:{})}));
  f.bets=empty?[]:['OPEN','OPEN','OPEN','RESULT_PUBLISHED','SETTLED','CANCELLED'].map((state,index)=>({id:uid(20+index),groupId:group.id,authorId:uid(1),question:['Vai chover no domingo?','Quem ganha a rodada?','O time chegou?','Resultado em discussão','Rodada concluída','Aposta cancelada'][index],type:'BINARY',minimumPoints:10,publishedAt:new Date(now-index*1000).toISOString(),endsAt:new Date(index===1?now+600000:index===2?now-600000:now+3600000).toISOString(),predictionsCloseAt:new Date(index===1||index===2?now-60000:now+1800000).toISOString(),authorCancelUntil:new Date(now-1000).toISOString(),state,options:[{id:uid(100+index*2),label:'Sim',position:0},{id:uid(101+index*2),label:'Não',position:1}]}));
  const aggregates=bet=>({betId:bet.id,totalVotes:f.votes.reduce((a,b)=>a+b,0),options:bet.options.map((option,i)=>({optionId:option.id,label:option.label,position:i,votes:f.votes[i]}))});
  const failResponse=route=>route.fulfill({status:503,json:{code:'UNAVAILABLE',message:'Consulta indisponível.'}});
  page.on('pageerror',error=>f.errors.push(error.message));
  await page.addInitScript(session=>sessionStorage.setItem('gamblr.session',JSON.stringify(session)),{access_token:'member',refresh_token:'member',expires_at:Math.floor(now/1000)+3600,user:{email:'ana@example.test',user_metadata:{display_name:'Ana'}}});
  await page.route('**/status',route=>route.fulfill({json:{writer:true,reader:true}}));
  await page.route('**/reader/api/**',route=>{
    const path=new URL(route.request().url()).pathname;
    if(path.endsWith('/participation'))return f.failParticipation?failResponse(route):route.fulfill({json:{groupId:group.id,bets:f.bets.map(aggregates)}});
    if(path.endsWith('/ranking'))return f.failRanking?failResponse(route):route.fulfill({json:{groupId:group.id,ranking:f.ranking}});
    const bet=f.bets.find(bet=>path.includes(bet.id));
    return route.fulfill({json:{...aggregates(bet),groupId:group.id,version:'v1',options:aggregates(bet).options.map(option=>({...option,decimalOdds:'2.00'}))}});
  });
  await page.route('**/writer/api/**',route=>{
    const path=new URL(route.request().url()).pathname;let json={};
    if(path.endsWith('/api/me'))json=profile;
    else if(path.endsWith('/api/groups')||path.endsWith('/api/groups/public'))json=[group];
    else if(path.endsWith('/api/friendships')||path.endsWith('/api/group-invitations')||path.endsWith('/join-requests'))json=[];
    else if(path.endsWith('/settings'))json={...group,myRole:empty?'OWNER':'MEMBER',canInvite:empty,initialPoints:100,dailyPoints:0,settlementSeconds:60,contestSeconds:60,visibility:'PUBLIC',entryMode:'IMMEDIATE',invitePolicy:'MODERATORS'};
    else if(path.endsWith('/wallet/me'))json={availablePoints:f.ranking.find(row=>row.userId===profile.id)?.availablePoints||'85',reservedPoints:0};
    else if(path.endsWith('/bets')){if(f.failBets)return failResponse(route);json=f.bets.map(bet=>({bet,myPrediction:null}));}
    else if(path.endsWith('/messages')){if(f.failMessages)return failResponse(route);json={messages:[],nextBefore:null,canModerate:empty};}
    else if(path.endsWith('/resolution')){const bet=f.bets.find(bet=>path.includes(bet.id));json={state:bet.state,phase:'IN_PROGRESS',resultDeadline:new Date(now+7200000).toISOString(),pendingContests:0,canPublishResult:false,canCancel:false,canDecide:false,canContest:false};}
    else if(path.endsWith('/members'))json=f.ranking.map(row=>({...row,role:empty?'OWNER':'MEMBER'}));
    else if(path.endsWith('/personalization'))json={prices:{nameColor:null,title:null,titleColor:null},style:{nameColor:null,title:null,titleColor:null}};
    else return route.fulfill({status:404,json:{code:'NOT_FOUND',message:'Não encontrado.'}});
    return route.fulfill({json});
  });
  await page.goto('/');await expect(page.locator('#page-title')).toHaveText(group.name);
  return f;
}

test('cards show all situations and live people shares in group and Feed; ranking preserves personal colors',async({page},testInfo)=>{
  const f=await workspace(page);
  await page.locator('#bet-filter').selectOption('all');
  await expect(page.locator('#bets-list .bet-card')).toHaveCount(6);
  for(const key of ['open','closed','awaiting','published','settled','cancelled'])await expect(page.locator(`#bets-list .status-${key}`)).toHaveCount(1);
  const card=page.locator('#bets-list .bet-card').first();
  await expect(card.locator('.participation-legend')).toContainText('Sim: 3 palpites · 75%');
  await expect(card.locator('.participation-legend')).toContainText('Não: 1 palpite · 25%');
  await expect(page.locator('#ranking-list .podium')).toHaveCount(3);
  await expect(page.locator('#ranking-summary')).toHaveText('Você está em 4º lugar, a 15 pontos do próximo colocado.');
  await expect(page.locator('#ranking-list .me .group-person > span').first()).toHaveCSS('color','rgb(18, 52, 86)');
  await expect(page.locator('#ranking-list .me .group-title')).toHaveCSS('color','rgb(171, 205, 239)');
  f.votes=[2,2];await expect(card.locator('.participation-legend')).toContainText('Sim: 2 palpites · 50%',{timeout:10000});
  f.ranking[3].availablePoints='100';await page.locator('#refresh-button').click();await expect(page.locator('#ranking-summary')).toContainText('empatado em pontos');
  f.ranking=[{...f.ranking[3],position:1,availablePoints:'150'},...f.ranking.slice(0,3).map((row,i)=>({...row,position:i+2}))];
  await page.locator('#refresh-button').click();await expect(page.locator('#ranking-summary')).toContainText('Você está em 1º lugar');
  await page.screenshot({path:testInfo.outputPath('cards-ranking-desktop.png'),fullPage:true});
  await page.locator('#feed-button').click();
  await expect(page.locator('#feed-list .feed-bet-card')).toHaveCount(6);
  await expect(page.locator('#feed-list .feed-bet-card').first().locator('.participation-legend')).toContainText('50%');
  await expect(page.locator('#feed-list .status-settled')).toHaveText('Concluída');
  await page.locator('#feed-query').fill('não existe');await expect(page.locator('#feed-list .empty-title')).toHaveText('Nenhuma aposta corresponde aos filtros.');
  await page.locator('#feed-list').getByRole('button',{name:'Limpar filtros',exact:true}).click();await expect(page.locator('#feed-list .feed-bet-card')).toHaveCount(6);
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await page.screenshot({path:testInfo.outputPath('feed-mobile.png'),fullPage:true});expect(f.errors).toEqual([]);
});

test('empty group guides publishing, authorized invitation and starting a conversation',async({page},testInfo)=>{
  const f=await workspace(page,{empty:true});
  await expect(page.locator('#bets-list .empty-illustration')).toBeVisible();
  await page.locator('#bets-list').getByRole('button',{name:'Publicar aposta'}).click();await expect(page.locator('#bet-dialog')).toBeVisible();await page.keyboard.press('Escape');
  await page.locator('#bets-list').getByRole('button',{name:'Convidar integrante'}).click();await expect(page.locator('#invite-search')).toBeFocused();await page.keyboard.press('Escape');
  await page.locator('#group-thread').getByRole('button',{name:'Escrever mensagem'}).click();await expect(page.locator('#group-message')).toBeFocused();
  await expect(page.locator('#ranking-list .podium')).toHaveCount(1);await expect(page.locator('#ranking-summary')).toContainText('Você está em 1º lugar');
  await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await page.screenshot({path:testInfo.outputPath('empty-group-mobile.png'),fullPage:true});expect(f.errors).toEqual([]);
});

test('failed loads offer retries and never claim zero participation or empty content',async({page})=>{
  const f=await workspace(page,{fail:true});
  const card=page.locator('#bets-list .bet-card').first();
  await expect(card).toContainText('Participação indisponível');await expect(card).not.toContainText('Sem palpites');
  await expect(page.locator('#ranking-error .load-failure')).toBeVisible();await expect(page.locator('#ranking-summary')).toBeHidden();
  await expect(page.locator('#group-thread .load-failure')).toBeVisible();
  await expect(page.locator('#bets-list').getByRole('button',{name:'Convidar integrante'})).toHaveCount(0);
  f.failParticipation=false;f.failRanking=false;f.failMessages=false;f.votes=[0,0];
  await page.locator('#refresh-button').click();
  await expect(card).toContainText('Sem palpites ainda');await expect(card.locator('.participation-segment')).toHaveCount(0);await expect(card.locator('.participation-legend')).not.toContainText('%');
  await expect(page.locator('#ranking-error')).toBeHidden();await expect(page.locator('#group-thread .load-failure')).toHaveCount(0);
  f.failBets=true;await page.locator('#refresh-button').click();await expect(page.locator('#bets-list .load-failure')).toBeVisible();
  await page.locator('#feed-button').click();await expect(page.locator('#feed-list .load-failure')).toBeVisible();
  await expect(page.locator('#feed-status')).not.toContainText('Nenhuma aposta disponível');expect(f.errors).toEqual([]);
});
