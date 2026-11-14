# Modelo inicial do banco

Fonte de produto: [requisitos.md](requisitos.md). Este modelo prepara a persistência
do fluxo de aposta de grupo. Não constitui uma implementação completa dos requisitos.

## Tabelas e consultas

| Objeto | Finalidade | Requisitos relacionados |
| --- | --- | --- |
| `app_users` | Identidade interna e data de criação da conta | RF-20, RF-30 |
| `user_identities` (V005) | Vínculo emissor/subject de JWT validado com o usuário interno | RF-30 |
| `groups` | Dono, regras de entrada, pontos e prazos configuráveis | RF-01, RF-02, RF-17, RF-26, RF-31 |
| `memberships` | Integrante, moderação, primeira entrada e entrada/saída mais recente | RF-03, RF-18, RF-19, RF-27, RF-29 |
| `wallets` | Saldo disponível e reservado por usuário/grupo | RF-01, RF-03, RF-09, RF-19 |
| `bets` | Proposta, prazos copiados, resultado publicado/final e estado | RF-06, RF-10, RF-22 a RF-26 |
| `bet_options` | Opções vinculadas à aposta | RF-23 |
| `predictions` | Escolha definitiva e valor de cada participante | RF-08, RF-09, RF-14 |
| `point_movements` | Histórico de créditos, reservas, pagamentos, perdas e devoluções | RF-01, RF-02, RF-07, RF-09, RF-12, RF-21 |
| `contests` | Contestação pendente, resolvida ou retirada | RF-11 |
| `contest_decisions` | Decisões do dono e opção final correspondente | RF-22 |
| `friendships`, `group_messages`, `private_messages` (V007) | Amizades e mensagens; `bet_id` vincula publicações ao chat na V009 | RF-16, RF-18, RF-23, RF-31 |
| `membership_departures` (V008) | Saídas preservadas para apuração | RF-10, RF-11, RF-19 |
| `group_invitations`, `group_join_requests` (V009) | Convites e solicitações de entrada | RF-17, RF-18 |
| `membership_periods`, `group_daily_rates`, `group_daily_runs` (V009) | Participações, valores históricos e processamento diário | RF-02 |
| `current_group_members` | Participação atual e vínculo emissor/subject para autorização do Flask (V006) | RF-13, RF-15 |
| `group_ranking` | Ranking de membros atuais por saldo disponível e antiguidade da conta | RF-20 |
| `bet_odds` | Votos e odds agregados por opção, sem valor/autor dos palpites | RF-05, RF-13, RF-14 |

As consultas devem usar o prefixo `gamblr.`, por exemplo `gamblr.bets`.

## Escolhas técnicas desta etapa

- IDs UUID gerados pelo PostgreSQL; não há referência a `auth.users` nem coluna de
  senha. O provedor escolhido é Supabase Auth; `user_identities` vincula o subject
  do token validado ao perfil interno. `app_users` não armazena credenciais de login.
- Pontos em `numeric`, finitos e não negativos. Não fixamos casas decimais nem
  valores padrão de crédito, mínimo ou prazos do grupo. A regra de arredondamento
  para baixo do RF-07 continua aplicável à parcela do prêmio. A aceitação de
  valores fracionários ou zero na interface precisa ser explicitada antes de
  implementar as validações de entrada, sem alterar o requisito do mínimo.
- Instantes com `timestamptz`; duração dos prazos com `interval`. O backend usará
  durações em segundos e calculará a data de crédito diário em `America/Sao_Paulo`.
- A linha do integrante e a carteira permanecem após saída. `left_at` marca sua
  ausência; `first_joined_at` preserva antiguidade e `joined_at` marca a entrada
  mais recente. O dono é indicado em `groups.owner_id`; a role de integrante
  guarda apenas `MEMBER` ou `MODERATOR`, evitando dois indicadores de dono.
- Não há exclusões em cascata. Não remover registros ao sair de grupo. O fluxo
  de exclusão do grupo e sua política de retenção ainda precisam de implementação.
- Não há taxa, compra/saque, redistribuição das sobras ou prêmios internos.

## O que o banco já garante

- Saldo e reserva nunca negativos; valores especiais como infinito/NaN rejeitados.
- Uma carteira e uma linha de integrante por usuário/grupo.
- Dono possui uma linha de integrante; a criação de ambos ocorre na mesma transação.
- Palpite único por aposta/usuário; opção e carteira pertencem à aposta/grupo corretos.
- Writer não pode editar/apagar palpites confirmados nem o histórico de movimentações.
- Crédito inicial uma vez por usuário/grupo; diário uma vez por usuário/grupo/data.
- Uma reserva e uma movimentação de conclusão por palpite, com chave de operação
  para evitar repetição. O primeiro evento de conclusão bloqueia outro pagamento,
  perda ou devolução para o mesmo palpite.
- Prazos de 10%/90% consistentes com publicação/término; resultados pertencem à aposta.
- Flask lê somente as três views. Ele não recebe acesso direto às tabelas privadas.
  Opção sem votos tem odd `NULL`, a ser exibida como “—”. A apresentação decide
  como formatar as casas decimais, mantendo o cálculo pela contagem de votos.

## Regras que ainda dependem do backend

O [serviço Spring Boot](writer.md) já implementa publicação, entrada pública
imediata, provisionamento de perfil, reserva transacional, resultado, contestação
e conclusão financeira. A V009 acrescenta administração e crédito diário,
descritos em [administracao.md](administracao.md).

As FKs preservam vínculos históricos; elas não garantem que o integrante esteja
ativo no momento de uma operação. Cada endpoint precisará validar a identidade,
a participação atual e as permissões de autor, moderador ou dono.

O Spring confirma palpites em uma única transação: bloquear carteira
e aposta em ordem consistente, conferir prazo, autor, estado, valor mínimo e saldo,
registrar o palpite e a reserva e atualizar os saldos. A conferência do prazo deve
usar o horário do servidor depois de obter os bloqueios. Os testes de integração
Java exercitam esse protocolo, concorrência e rollback em schema temporário.

O serviço publica aposta/opções atomicamente, oferece Sim/Não para binárias e
copia os prazos do grupo. Não expõe endpoints de edição da proposta, opções ou
palpites. O ciclo de resultado, retirada de contestações por saída e conclusão
automática está implementado em [resultados.md](resultados.md). Falta definir
a interface definitiva. Crédito diário, transferência/sucessão e
as APIs de saída/remoção estão implementados pela V009.

O histórico não atualiza automaticamente a carteira, nem a carteira gera histórico.
Essas escritas devem ser feitas juntas na mesma transação. Isso já ocorre na
reserva, no crédito inicial e nas conclusões financeiras. As restrições impedem
duplicações e saldos negativos, mas não
substituem essas transações.

O Flask deve filtrar cada consulta pelo grupo autorizado. A credencial de leitura
é uma credencial de servidor e consegue consultar agregados de vários grupos;
ela nunca será entregue ao navegador. As views não decidem quem está autenticado.

Os estados persistidos da aposta são `OPEN`, `RESULT_PUBLISHED`, `SETTLED` e
`CANCELLED`. Com estado `OPEN`, o prazo determina se aceita palpites, aguarda
término ou aguarda resultado; essa derivação evita depender de um job para
rejeitar palpites depois dos 90%.

Se houver nova contestação após uma decisão antecipada, cria-se novo registro;
só pode haver uma pendente por participante/aposta. O backend garante
que só contestações pendentes na decisão sejam resolvidas por ela.

A V007 acrescenta amizades e mensagens de grupo/privadas, com autorização no
Spring e RLS restrita à conta técnica do writer. Consulte [chats.md](chats.md).
Convites e pedidos de entrada são acrescentados pela V009.

A V008 acrescenta o motivo da conclusão e os eventos de saída usados pelo
processamento automático. As tabelas de resultados e contestações da V003 são
reutilizadas. Prêmios internos permanecem em aberto.

A V009 guarda períodos de participação e valores diários históricos, registra
as datas processadas e oculta grupos/apostas excluídos sem apagar carteiras ou
movimentos. Os eventos de publicação de apostas aparecem no chat.
