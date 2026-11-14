export function filterFeed(entries, {status='all',groups=[],from='',to='',query=''}={}, now=Date.now()) {
  return entries.filter(({group,bet})=>{
    const open=bet.state==='OPEN'&&new Date(bet.predictionsCloseAt).getTime()>now;
    if(groups.length&&!groups.includes(group.id))return false;
    if(status==='active'&&!['OPEN','RESULT_PUBLISHED'].includes(bet.state))return false;
    if(status==='open'&&!open)return false;
    if(status==='awaiting'&&!(bet.state==='OPEN'&&new Date(bet.endsAt).getTime()<=now))return false;
    if(['RESULT_PUBLISHED','SETTLED','CANCELLED'].includes(status)&&bet.state!==status)return false;
    const published=new Date(bet.publishedAt);
    if(from&&published<new Date(`${from}T00:00:00`))return false;
    if(to){const end=new Date(`${to}T00:00:00`);end.setDate(end.getDate()+1);if(published>=end)return false;}
    return bet.question.toLocaleLowerCase('pt-BR').includes(query.trim().toLocaleLowerCase('pt-BR'));
  });
}
