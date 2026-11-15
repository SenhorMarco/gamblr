// Aproxima mensagens adjacentes sem juntar conteudos, autores, datas ou acoes.
export function continuesMessage(previous,current) {
  if(!previous||previous.authorId!==current.authorId||previous.deleted||current.deleted||previous.betId||current.betId)return false;
  const before=new Date(previous.sentAt),after=new Date(current.sentAt),gap=after-before;
  return Number.isFinite(gap)&&gap>=0&&gap<=5*60*1000&&before.toDateString()===after.toDateString();
}
