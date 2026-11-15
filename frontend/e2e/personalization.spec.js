import {test,expect} from '@playwright/test';
const uid=n=>`30000000-0000-0000-0000-${String(n).padStart(12,'0')}`;

test('group personalization configures prices, retries a purchase and removes without refund',async({page},testInfo)=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  const person={id:uid(1),displayName:'Marco'},group={id:uid(2),ownerId:uid(1),name:'Personalizações',member:true,memberCount:1,visibility:'PUBLIC',entryMode:'IMMEDIATE'};
  const style={nameColor:null,title:null,titleColor:null};let prices={nameColor:null,title:null,titleColor:null},balance=100,loseResponse=true;
  const purchases=new Map();
  await page.addInitScript(session=>sessionStorage.setItem('gamblr.session',JSON.stringify(session)),{access_token:'owner',refresh_token:'owner',expires_at:Math.floor(Date.now()/1000)+3600,user:{email:'owner@example.test',user_metadata:{display_name:'Marco'}}});
  await page.route('**/status',route=>route.fulfill({json:{writer:true,reader:true}}));
  await page.route('**/reader/api/**',route=>route.fulfill({json:{ranking:[{...person,...style,userId:person.id,position:1,availablePoints:balance}]}}));
  await page.route('**/writer/api/**',async route=>{
    const req=route.request(),path=new URL(req.url()).pathname.replace('/writer',''),method=req.method(),body=method==='POST'?req.postDataJSON():null;let json={},status=200;
    if(path==='/api/me')json=person;
    else if(path==='/api/groups')json=[group];
    else if(path==='/api/friendships'||path==='/api/group-invitations'||path.endsWith('/join-requests')||path.endsWith('/bets'))json=[];
    else if(path.endsWith('/settings'))json={...group,initialPoints:100,dailyPoints:0,settlementSeconds:60,contestSeconds:60,invitePolicy:'ALL_MEMBERS',myRole:'OWNER',canInvite:true};
    else if(path.endsWith('/wallet/me'))json={availablePoints:balance,reservedPoints:0};
    else if(path.endsWith('/members'))json=[{userId:person.id,displayName:person.displayName,role:'OWNER',...style}];
    else if(path.endsWith('/messages'))json={messages:[{id:1,authorId:person.id,displayName:person.displayName,body:'Olá!',sentAt:'2026-11-15T12:00:00Z',...style}],nextBefore:null,canModerate:true};
    else if(path.endsWith('/personalization'))json={prices,style};
    else if(path.endsWith('/personalization/prices'))prices=body;
    else if(path.endsWith('/personalization/purchases')){
      if(!purchases.has(body.clientId)){
        const key={NAME_COLOR:'nameColor',TITLE:'title',TITLE_COLOR:'titleColor'}[body.action];
        balance-=Number(prices[key]);style[key]=body.value;purchases.set(body.clientId,body);
        if(loseResponse){loseResponse=false;return route.abort();}
      }
    }
    else if(path.endsWith('/title/remove')){style.title=null;style.titleColor=null;}
    else{status=404;json={code:'NOT_FOUND',message:'Rota não simulada'};}
    await route.fulfill({status,json});
  });
  await page.goto('/');await expect(page.locator('#page-title')).toHaveText(group.name);
  await page.locator('#manage-group-button').click();
  const panel=page.locator('.personalization');
  await expect(panel.getByRole('button',{name:'Preço não configurado'})).toHaveCount(3);
  const config=panel.locator('form').last();
  await config.locator('[name=nameColor]').fill('10');await config.locator('[name=title]').fill('20');await config.locator('[name=titleColor]').fill('5');
  await config.getByRole('button',{name:'Salvar preços'}).click();
  const titleForm=panel.locator('form').filter({has:page.locator('input[type=text]')});
  await expect(titleForm.getByRole('button')).toHaveText('Comprar alteração · 20 pontos');
  await titleForm.locator('input').fill('Campeão');await titleForm.getByRole('button').click();
  await expect(page.locator('#manage-error')).toBeVisible();expect(balance).toBe(80);
  await titleForm.getByRole('button').click();
  await expect(page.locator('#available-points')).toHaveText('80');expect(purchases.size).toBe(1);
  const colorForm=label=>panel.locator('form').filter({has:page.getByLabel(label,{exact:true})}).first();
  await colorForm('Cor do nome').locator('input').fill('#123456');await colorForm('Cor do nome').getByRole('button').click();
  await expect(page.locator('#available-points')).toHaveText('70');
  await colorForm('Cor do título').locator('input').fill('#abcdef');await colorForm('Cor do título').getByRole('button').click();
  await expect(page.locator('#available-points')).toHaveText('65');
  await expect(page.locator('#manage-members .group-title')).toHaveText('Campeão');
  await expect(page.locator('#manage-members .group-title')).toHaveCSS('color','rgb(171, 205, 239)');
  await panel.scrollIntoViewIfNeeded();await page.screenshot({path:testInfo.outputPath('personalization-desktop.png'),fullPage:true});
  await page.setViewportSize({width:390,height:844});
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
  await panel.scrollIntoViewIfNeeded();await page.screenshot({path:testInfo.outputPath('personalization-mobile.png'),fullPage:true});
  await page.keyboard.press('Escape');
  await expect(page.locator('#group-thread .group-title')).toHaveText('Campeão');
  await expect(page.locator('#ranking-list .group-title')).toHaveText('Campeão');
  await expect(page.locator('#ranking-list .group-person > span').first()).toHaveCSS('color','rgb(18, 52, 86)');
  await page.locator('#manage-group-button').click();await panel.getByRole('button',{name:'Remover meu título sem reembolso'}).click();
  await expect(page.locator('#manage-members .group-title')).toHaveCount(0);expect(balance).toBe(65);expect(style.titleColor).toBeNull();expect(style.nameColor).toBe('#123456');
  await page.keyboard.press('Escape');await expect(page.locator('#ranking-list .group-title')).toHaveCount(0);
  await page.locator('#manage-group-button').click();
  await config.locator('[name=nameColor]').fill('0.0001');await config.locator('[name=title]').fill('0');await config.locator('[name=titleColor]').fill('0');
  await config.getByRole('button',{name:'Salvar preços'}).click();
  await expect(colorForm('Cor do nome').getByRole('button')).toHaveText('Comprar alteração · 0,0001 pontos');
  await expect(titleForm.getByRole('button')).toHaveText('Comprar alteração · 0 pontos');
  await titleForm.locator('input').fill('Título gratuito');await titleForm.getByRole('button').click();
  await expect(page.locator('#manage-members .group-title')).toHaveText('Título gratuito');expect(balance).toBe(65);
  expect(errors).toEqual([]);
});
