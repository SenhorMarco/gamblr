# Resultado, contestação e conclusão das apostas

O Spring implementa o ciclo de apuração, contestação, cancelamento, devolução e
pagamento. As regras são as de [requisitos.md](requisitos.md); odds continuam sendo
calculadas pelo Flask por quantidade de votos, independentemente do pagamento.

## Ativar

1. Com V001–V007 já aplicadas, execute o arquivo completo
   [V008__bet_resolution.sql](../database/migrations/V008__bet_resolution.sql)
   no SQL Editor do Supabase como `postgres`. A migração adiciona o motivo da
   conclusão e o registro de saídas do grupo, com RLS e permissões já configuradas.
   Não execute novamente as migrações anteriores.
2. Reinicie o Spring e o servidor do frontend, parando as instâncias anteriores
   com Ctrl+C antes de iniciar:

   ```powershell
   .\.venv\Scripts\python.exe scripts/writer.py bootRun
   ```

   ```powershell
   .\.venv\Scripts\python.exe scripts/frontend.py
   ```

3. Recarregue o site. O Flask de ranking e odds pode continuar em execução.

A aplicação não aplica migrações automaticamente. A V008 só foi aplicada pelos
testes a um schema temporário, não ao schema `gamblr` do ambiente existente.

## Testar pela interface

1. Crie um grupo e, em **Configurações de pontos e prazos**, informe os prazos de
   apuração e contestação. Para um teste rápido, use 1 minuto para cada um.
   Esses prazos são copiados para cada aposta quando ela é publicada.
2. Publique uma aposta com término próximo, mas com tempo para outra pessoa
   confirmar um palpite. Os palpites continuam fechando aos 90% da duração.
3. Depois do término, o autor encontra **Opção vencedora → Publicar resultado**
   no detalhe da aposta. A tela informa até quando o resultado pode ser publicado.
4. Um participante que apostou pode usar **Contestar resultado** durante o prazo.
   Só uma contestação por pessoa pode estar pendente ao mesmo tempo.
5. O dono do grupo encontra **Decisão do dono**: manter o resultado publicado,
   escolher outra opção ou cancelar com devolução. Ele pode decidir mesmo que
   seja autor ou participante. Uma decisão resolve as contestações pendentes
   naquele momento; novas contestações podem ser feitas até o fim do prazo.
6. Decidir antes do prazo terminar não antecipa o pagamento nem a devolução.
   Isso inclui a decisão de cancelar. Sem contestação pendente ao fim do prazo,
   a conclusão acontece automaticamente e os saldos/ranking são atualizados.
7. O detalhe mostra o crédito ou devolução do próprio usuário, ou informa a perda.
   Use **Todas** para consultar apostas concluídas. O filtro **Ativas** inclui
   apostas que aguardam resultado, contestação ou decisão do dono.

Para testar o cancelamento automático, deixe o prazo do autor terminar sem
publicar resultado. Para testar o cancelamento pelo autor, use o botão disponível
somente nos primeiros 10% da duração. Os pontos são devolvidos integralmente.

## Prazos e recuperação

- O resultado pode ser publicado a partir do término e até o instante final do
  prazo de apuração, inclusive. Após esse instante, a aposta é cancelada.
- A contestação pode ser registrada desde a publicação do resultado até antes
  do instante final do prazo de contestação. Um prazo de zero segundos encerra
  essa janela imediatamente.
- Com contestações pendentes, o dono pode decidir mesmo após o prazo, sem limite.
  **Manter** usa o resultado originalmente publicado; **Escolher outra opção**
  define um novo resultado final.
- O Spring processa lotes de até 100 apostas, em rodízio, com intervalo de
  5 segundos entre lotes. O
  relógio é o do banco. O processamento ocorre sem página aberta, mas exige que
  o serviço Java esteja em execução. Ao reiniciar, ele retoma as apostas pendentes.
- Consultar o resultado de uma aposta também verifica seus prazos. Repetir o
  processamento não repete o pagamento ou a devolução.
- Saídas/remoções registradas em `memberships.left_at` geram um evento persistente.
  O autor que saiu antes de publicar o resultado tem a aposta cancelada, mesmo
  se voltar antes do processamento. Após publicar o resultado, sua saída não
  cancela a aposta.
- A saída de quem contestou retira sua contestação pendente, preservando o palpite.
  Se era a última pendente, o sistema aguarda o fim do prazo ou conclui no próximo
  processamento caso o prazo já tenha terminado. As carteiras de ex-integrantes
  também recebem ganhos e devoluções. A interface de saída/remoção ainda não existe.
- A migração registra as saídas atualmente conhecidas em `left_at`. Saídas e
  retornos anteriores à V008 cujo histórico já foi sobrescrito não são reconstruídos.

## Distribuição e privacidade

O crédito de um vencedor é o valor apostado mais a parcela dos pontos perdidos:

```text
crédito = valor apostado + floor(pontos perdidos × valor apostado / total apostado pelos vencedores)
```

A parcela é arredondada para baixo em pontos inteiros; a sobra não é distribuída.
O valor apostado original, inclusive frações, é recuperado integralmente. No
exemplo dos requisitos, vencedores com 10 e 30 pontos e perdedores com 80 no total
recebem créditos de 30 e 90. A odd decimal por votos não é usada nessa conta.

Sem palpites na opção vencedora, os pontos perdidos não são distribuídos. Conforme
a decisão confirmada no RF-07, se os vencedores apostaram apenas zero pontos,
também não há distribuição: recebem zero e os perdedores perdem os pontos reservados.

Cada conclusão bloqueia a aposta e as carteiras em ordem estável e grava, na mesma
transação, estado, movimentos financeiros e saldos. Uma falha desfaz tudo e permite
nova tentativa. O índice único de conclusão por palpite protege contra duplicação.
As APIs mostram apenas a contagem agregada de contestações e o movimento financeiro
do próprio usuário, sem expor escolhas ou valores de outras pessoas.

## Endpoints autenticados

| Método | Rota | Operação |
| --- | --- | --- |
| GET | `/api/bets/{id}/resolution` | Estado, prazos, contagem, permissões e crédito próprio |
| POST | `/api/bets/{id}/result` | Autor publica `{ "optionId": "UUID" }` |
| POST | `/api/bets/{id}/contests` | Participante registra contestação, sem corpo |
| POST | `/api/bets/{id}/decision` | Dono decide `{ "action": "KEEP" }`, `CHANGE` com `optionId`, ou `CANCEL` |
| POST | `/api/bets/{id}/cancel` | Autor cancela no prazo, sem corpo |

Todos exigem participação atual. Autor, prazo, estado e papel de dono são conferidos
no servidor após os locks; o frontend não decide autorização. As operações retornam
a mesma visão de resultado do GET. Repetir uma publicação, decisão já resolvida ou
cancelamento concluído é rejeitado sem nova movimentação.

## Verificação

Os testes Java usam relógio controlado, HTTP autenticado e PostgreSQL real em schema
temporário. Cobrem permissões, prazos, decisões antes/depois do prazo, nova contestação,
saídas/retornos, rateio, arredondamento, falta de vencedores, palpites de zero,
concorrência de conclusões, idempotência e rollback de falha financeira. O teste de
navegador usa respostas simuladas para exercitar formulários, duas sessões e mobile.

```powershell
.\.venv\Scripts\python.exe scripts/writer.py integration
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
```

Com o frontend aberto, na pasta `frontend`:

```powershell
.\node_modules\.bin\playwright.cmd test
```

Crédito diário, convites e administração de membros foram acrescentados pela
V009, conforme [administracao.md](administracao.md). A arquitetura definitiva do
frontend permanece em aberto. Cores e títulos por pontos são acrescentados pela
V011, conforme [personalização](personalizacao.md); outros prêmios permanecem em aberto.
