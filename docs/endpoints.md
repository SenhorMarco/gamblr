# Aplicação de todos os endpoints Java e Python

Catálogo dos 51 endpoints de backend do Gamblr: **47 Java e 4 Python**.
As rotas foram conferidas contra todos os controllers Spring e decorators Flask.
Em 15/11/2026, todas as 51 rotas responderam com sucesso em chamadas HTTP reais
no ambiente de integração isolado descrito ao final deste documento.

## Como as APIs trabalham juntas

O Java (`services/writer`, porta 8080) controla identidades internas, grupos,
carteiras, apostas, mensagens, administração e personalização. O Python
(`services/reader`, porta 5000) consulta ranking e agregados autorizados.
O navegador usa o proxy do site na porta 5173: `/writer` é encaminhado ao Java
e `/reader` ao Python. Os caminhos abaixo são os caminhos diretos dos backends.
O login é externo às duas APIs, no Supabase Auth.

## Convenções de acesso

- Fora de `/health`, use `Authorization: Bearer ACCESS_TOKEN` de um usuário.
  No Java, provisione antes o perfil com `POST /api/me`.
- POSTs com corpo usam `Content-Type: application/json`. Resposta vazia não é
  um JSON vazio: os status 200 sem corpo e 204 não devem ser passados a json().
- Identificadores de grupos, usuários, apostas, opções, convites e pedidos são UUIDs.
  IDs de mensagens e seus cursores são inteiros. Datas são instantes ISO 8601.
- 400 indica entrada inválida; 401 falha de autenticação; 403 acesso negado;
  404 recurso indisponível; 409 conflito com estado/saldo; 503 falha de dependência.
  Erros de negócio usam `{code,message}`. Erros da camada de segurança Java
  podem não ter o mesmo corpo JSON.
- O usuário é identificado pelo JWT, não por um userId livre escolhido no corpo.
  Valores financeiros respeitam a carteira independente de cada grupo.

## Modelos usados nas respostas Java

| Modelo | Campos |
| --- | --- |
| Profile | id, displayName |
| GroupView | id, ownerId, name |
| GroupSummary | id, ownerId, name, visibility, entryMode, member, memberCount, requestPending |
| WalletView | groupId, userId, availablePoints, reservedPoints |
| BetView | id, groupId, authorId, question, type, minimumPoints, publishedAt, endsAt, predictionsCloseAt, authorCancelUntil, state, verificationCriterion, options |
| Opção em BetView | id, label, position |
| PredictionView | id, betId, optionId, points, confirmedAt |
| Friendship | id, userId, displayName, status, incoming |
| MessagePage | messages, nextBefore, canModerate |
| Mensagem | id, authorId, displayName, body, sentAt, deleted, betId, nameColor, title, titleColor |
| Settings | id, ownerId, name, initialPoints, dailyPoints, settlementSeconds, contestSeconds, visibility, entryMode, invitePolicy, myRole, canInvite |
| Member | userId, displayName, role, firstJoinedAt, nameColor, title, titleColor |
| Exit | successorId (pode ser null), groupDeleted |
| Resolution | state, phase, resultDeadline, contestDeadline, publishedOptionId, finalOptionId, concludedAt, conclusionReason, pendingContests, myPendingContest, canPublishResult, canContest, canDecide, canCancel, myCredit, myMovement, lastDecisionAction |

`GroupInput` (usado tanto para criar quanto para configurar um grupo):

```json
{"name":"Turma","initialPoints":100,"dailyPoints":0,"settlementSeconds":600,"contestSeconds":600,"visibility":"PUBLIC","entryMode":"IMMEDIATE","invitePolicy":"ALL_MEMBERS"}
```

Visibilidade: PUBLIC/PRIVATE; entrada: IMMEDIATE/REQUEST/INVITE; convite:
ALL_MEMBERS/MODERATORS. PRIVATE exige INVITE. Prazos e valores não são negativos.

`BetInput` (use um endsAt futuro ao testar):

```json
{"question":"Vai acontecer?","type":"BINARY","options":null,"verificationCriterion":"Fonte opcional","endsAt":"2030-01-01T20:00:00Z","minimumPoints":10}
```

MULTIPLE_CHOICE exige pelo menos duas opções distintas e preenchidas em options.
`MessageInput` exige body não vazio, de até 4.000 caracteres, e clientId UUID:

```json
{"body":"Olá, turma!","clientId":"00000000-0000-4000-8000-000000000001"}
```

Os UUIDs e a data acima são exemplos de formato. Recursos devem ser obtidos das
respostas anteriores; cada nova operação idempotente recebe sua própria chave.

## Java — Perfil, grupos e apostas

### 01. `GET /health`

Verifica se o processo Java responde. Usado pelo supervisor e pelo status do site.

- **Entrada:** Sem corpo ou autenticação.
- **Resposta:** 200; `{"status":"UP"}`.
- **Acesso e regras:** Não consulta o banco; não comprova sozinho a disponibilidade do PostgreSQL.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 02. `POST /api/me`

Provisiona o perfil interno após o login no Supabase. A interface chama esta rota antes de carregar os grupos.

- **Entrada:** `{"displayName":"Ana"}`.
- **Resposta:** 200; `Profile`.
- **Acesso e regras:** Exige JWT. Repetir a chamada retorna o perfil existente; não é uma rota de edição de nome.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 03. `POST /api/groups`

Cria a turma, registra o dono como integrante e concede seu crédito inicial. Usado em Criar grupo.

- **Entrada:** Objeto `GroupInput`.
- **Resposta:** 201; `GroupView`.
- **Acesso e regras:** Usuário com perfil. Grupo privado exige `entryMode=INVITE`. Crédito, grupo e vínculo são transacionais.
- **Verificação:** HTTP 201 recebido no ambiente isolado.

### 04. `GET /api/groups`

Lista os grupos em que o usuário participa atualmente. Alimenta o menu e a composição do Feed.

- **Entrada:** Sem corpo.
- **Resposta:** 200; lista de `GroupSummary`; `[]` se não houver grupos.
- **Acesso e regras:** Usuário com perfil. Grupos excluídos são omitidos.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 05. `GET /api/groups/public`

Oferece grupos públicos para exploração e mostra se o usuário já participa ou tem solicitação pendente.

- **Entrada:** Sem corpo.
- **Resposta:** 200; lista de `GroupSummary`.
- **Acesso e regras:** Usuário com perfil; grupos privados e excluídos não aparecem.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 06. `POST /api/groups/{group}/members/me`

Entra em grupo público de entrada imediata. Serve ao botão Entrar no grupo.

- **Entrada:** `group`: UUID; sem corpo.
- **Resposta:** 200; `WalletView`.
- **Acesso e regras:** Somente entrada pública imediata. Reentrada preserva saldo e não repete o crédito inicial; grupo por solicitação usa outra rota.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 07. `GET /api/groups/{group}/wallet/me`

Mostra os pontos disponíveis e reservados do próprio usuário nos cartões de saldo.

- **Entrada:** `group`: UUID; sem corpo.
- **Resposta:** 200; `WalletView`.
- **Acesso e regras:** Integrante atual; não permite consultar a carteira de outra pessoa.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 08. `POST /api/groups/{group}/bets`

Publica uma proposta no grupo e gera o evento correspondente no chat.

- **Entrada:** `group`: UUID; objeto `BetInput`.
- **Resposta:** 201; `BetView`, incluindo IDs das opções e prazos calculados.
- **Acesso e regras:** Integrante atual. Término futuro; opções válidas e distintas; binária usa Sim/Não. Dados publicados não são editáveis.
- **Verificação:** HTTP 201 recebido no ambiente isolado.

### 09. `GET /api/groups/{group}/bets`

Carrega as apostas do grupo, suas opções e o palpite do usuário. Usado pela listagem, detalhe e Feed.

- **Entrada:** `group`: UUID; sem corpo.
- **Resposta:** 200; lista de `BetCard`: `{bet: BetView, myPrediction: PredictionView|null}`.
- **Acesso e regras:** Integrante atual. Não retorna os palpites individuais dos demais; apostas ocultadas não são listadas.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 10. `GET /api/bets/{bet}`

Consulta diretamente uma aposta e suas opções. Na interface atual, esses dados normalmente já vêm na listagem do grupo.

- **Entrada:** `bet`: UUID; sem corpo.
- **Resposta:** 200; `BetView`.
- **Acesso e regras:** Integrante atual do grupo da aposta. Recurso inexistente/ocultado não fica disponível.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 11. `POST /api/bets/{bet}/predictions`

Confirma a escolha definitiva e reserva os pontos em uma única transação. Usado em Confirmar palpite.

- **Entrada:** `{"optionId":"UUID_DA_OPCAO","points":10}`.
- **Resposta:** 201; `PredictionView`.
- **Acesso e regras:** Integrante atual, diferente do autor; antes do fechamento aos 90% da duração. Exige opção da aposta, mínimo, saldo disponível e ausência de palpite anterior.
- **Verificação:** HTTP 201 recebido no ambiente isolado.

### 12. `GET /api/bets/{bet}/prediction/me`

Consulta exclusivamente o palpite do usuário nessa aposta. A interface normal também recebe esse objeto em `BetCard.myPrediction`.

- **Entrada:** `bet`: UUID; sem corpo.
- **Resposta:** 200; `PredictionView`; 404 se o usuário não confirmou palpite.
- **Acesso e regras:** Integrante atual. Não revela escolhas ou valores de outros participantes.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

## Java — Amizades e mensagens

### 13. `GET /api/users`

Pesquisa perfis para adicionar amigos ou convidar pessoas.

- **Entrada:** Query obrigatória `name`, de 2 a 80 caracteres; exemplo `/api/users?name=Ana`.
- **Resposta:** 200; até 20 objetos `{id,displayName}`.
- **Acesso e regras:** Usuário com perfil; exclui o próprio usuário e não expõe emails. O nome deve ser codificado na URL.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 14. `GET /api/friendships`

Alimenta a lista de amigos, pedidos recebidos e pedidos enviados.

- **Entrada:** Sem corpo.
- **Resposta:** 200; lista de `Friendship`.
- **Acesso e regras:** Usuário com perfil. Pedidos recusados são omitidos; `incoming` indica quem pode responder.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 15. `POST /api/friendships`

Envia ou retoma um pedido de amizade para outro perfil.

- **Entrada:** `{"userId":"UUID_DO_DESTINATARIO"}`.
- **Resposta:** 200; `Friendship`.
- **Acesso e regras:** Não permite autoamizade. Um pedido cruzado não aceita automaticamente a amizade; a chave ordenada evita duplicações.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 16. `POST /api/friendships/{id}/accept`

Aceita um pedido e libera o chat privado entre as duas pessoas.

- **Entrada:** `id`: UUID do pedido, não do usuário; sem corpo.
- **Resposta:** 204; sem corpo.
- **Acesso e regras:** Somente o destinatário de um pedido ainda pendente.
- **Verificação:** HTTP 204 recebido no ambiente isolado.

### 17. `POST /api/friendships/{id}/decline`

Recusa um pedido recebido; usado no botão Recusar.

- **Entrada:** `id`: UUID do pedido; sem corpo.
- **Resposta:** 204; sem corpo.
- **Acesso e regras:** Somente o destinatário de um pedido pendente. Outra solicitação pode ser enviada posteriormente.
- **Verificação:** HTTP 204 recebido no ambiente isolado.

### 18. `GET /api/groups/{group}/messages`

Carrega o chat de grupo desde a entrada atual do usuário.

- **Entrada:** `group`: UUID; query opcional `before`, ID numérico positivo de mensagem, para paginação.
- **Resposta:** 200; `MessagePage`.
- **Acesso e regras:** Integrante atual. Retorna até 100 mensagens em ordem cronológica e um cursor para mensagens anteriores; não revela histórico anterior à entrada.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 19. `POST /api/groups/{group}/messages`

Envia uma mensagem ao chat compartilhado.

- **Entrada:** Objeto `MessageInput`.
- **Resposta:** 204; sem corpo.
- **Acesso e regras:** Integrante atual. Mesma chave `clientId`, autor e conteúdo não duplica o envio; reutilizar chave com outro conteúdo gera conflito.
- **Verificação:** HTTP 204 recebido no ambiente isolado.

### 20. `POST /api/groups/{group}/messages/{id}/delete`

Oculta o corpo de uma mensagem e deixa o aviso de moderação no histórico.

- **Entrada:** `group`: UUID; `id`: inteiro positivo da mensagem; sem corpo.
- **Resposta:** 204; sem corpo.
- **Acesso e regras:** Dono ou moderador, para mensagem visível no próprio histórico. Exclusão lógica; não apaga o registro financeiro de uma aposta.
- **Verificação:** HTTP 204 recebido no ambiente isolado.

### 21. `GET /api/friends/{friend}/messages`

Carrega a conversa privada com um amigo.

- **Entrada:** `friend`: UUID do outro usuário; query opcional `before` numérica.
- **Resposta:** 200; `MessagePage`, com `canModerate=false`.
- **Acesso e regras:** Amizade explicitamente aceita. Personalizações de grupos não são aplicadas à conversa privada.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 22. `POST /api/friends/{friend}/messages`

Envia uma mensagem privada.

- **Entrada:** `friend`: UUID do outro usuário; objeto `MessageInput`.
- **Resposta:** 204; sem corpo.
- **Acesso e regras:** Amizade aceita; mesma proteção por `clientId` contra repetição do envio. Não há apostas em chat privado.
- **Verificação:** HTTP 204 recebido no ambiente isolado.

## Java — Configuração e integrantes

### 23. `GET /api/groups/{group}/settings`

Carrega as regras do grupo e as capacidades do usuário em Gerenciar grupo.

- **Entrada:** `group`: UUID; sem corpo.
- **Resposta:** 200; `Settings`.
- **Acesso e regras:** Integrante atual; `myRole` e `canInvite` orientam os controles disponíveis na interface.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 24. `POST /api/groups/{group}/settings`

Atualiza nome, valores de pontos, prazos e políticas de entrada e convite.

- **Entrada:** Objeto `GroupInput` completo.
- **Resposta:** 200; `Settings` atualizado.
- **Acesso e regras:** Somente o dono. Alterações não reescrevem saldo existente, créditos passados ou prazos de apostas já publicadas. Mudar para modo sem solicitações encerra pedidos pendentes.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 25. `GET /api/groups/{group}/members`

Mostra os integrantes atuais, seus papéis e personalizações.

- **Entrada:** `group`: UUID; sem corpo.
- **Resposta:** 200; lista de `Member`.
- **Acesso e regras:** Integrante atual. A lista identifica o dono como `OWNER`, além de `MODERATOR` e `MEMBER`.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 26. `POST /api/groups/{group}/members/{user}/role`

Promove ou destitui um moderador.

- **Entrada:** `user`: UUID do integrante; `{"role":"MODERATOR"}` ou `{"role":"MEMBER"}`.
- **Resposta:** 200; sem corpo.
- **Acesso e regras:** Somente o dono. Para alterar o papel do próprio dono, primeiro é necessário transferir a propriedade.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 27. `POST /api/groups/{group}/members/{user}/remove`

Remove um integrante e aplica os efeitos da saída sobre acesso, apostas e contestações.

- **Entrada:** `user`: UUID do integrante; sem corpo.
- **Resposta:** 200; `Exit`.
- **Acesso e regras:** Dono pode remover membros e moderadores; moderador apenas membros comuns. Não remove o dono nem substitui a rota de saída própria. Carteira e personalização são preservadas.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 28. `POST /api/groups/{group}/leave`

Permite sair do grupo; executa sucessão se quem sair for o dono.

- **Entrada:** `group`: UUID; sem corpo.
- **Resposta:** 200; `Exit`.
- **Acesso e regras:** Integrante atual. Sem sucessor indicado, prioriza moderador mais antigo e depois membro mais antigo. Último integrante saindo encerra logicamente o grupo.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 29. `POST /api/groups/{group}/transfer`

Transfere imediatamente a propriedade para outro integrante.

- **Entrada:** `{"userId":"UUID_DO_SUCESSOR"}`.
- **Resposta:** 200; sem corpo.
- **Acesso e regras:** Somente o dono; sucessor deve ser outro integrante atual. Não exige aceite, e o antigo dono torna-se moderador.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

## Java — Convites e solicitações

### 30. `POST /api/groups/{group}/invitations`

Convida uma pessoa para o grupo.

- **Entrada:** `{"userId":"UUID_DO_DESTINATARIO"}`.
- **Resposta:** 200; `{"id":"UUID_DO_CONVITE"}`.
- **Acesso e regras:** Conforme `invitePolicy`: todos os integrantes ou apenas dono/moderadores. Perfil precisa existir e não estar no grupo; convite pendente não é duplicado.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 31. `GET /api/group-invitations`

Mostra convites pendentes recebidos pelo usuário no menu.

- **Entrada:** Sem corpo.
- **Resposta:** 200; lista de `{id,groupId,groupName,senderName}`.
- **Acesso e regras:** Somente os convites do usuário autenticado para grupos disponíveis.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 32. `POST /api/group-invitations/{id}/accept`

Aceita o convite e admite o destinatário no grupo.

- **Entrada:** `id`: UUID do convite; sem corpo.
- **Resposta:** 200; `{"groupId":"UUID_DO_GRUPO"}`.
- **Acesso e regras:** Somente o destinatário, convite pendente. Primeira entrada concede crédito; retorno preserva carteira sem novo crédito inicial.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 33. `POST /api/group-invitations/{id}/decline`

Recusa um convite recebido.

- **Entrada:** `id`: UUID do convite; sem corpo.
- **Resposta:** 200; sem corpo.
- **Acesso e regras:** Somente o destinatário de convite pendente.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 34. `POST /api/groups/{group}/join-requests`

Solicita entrada em grupo público que exige aprovação.

- **Entrada:** `group`: UUID; sem corpo.
- **Resposta:** 200; `{"id":"UUID_DA_SOLICITACAO"}`.
- **Acesso e regras:** Usuário ainda não integrante; grupo deve usar `PUBLIC` e `REQUEST`. Uma solicitação pendente não é duplicada.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 35. `GET /api/groups/{group}/join-requests`

Lista solicitações pendentes para análise na administração.

- **Entrada:** `group`: UUID; sem corpo.
- **Resposta:** 200; lista de `{id,userId,displayName,nameColor,title,titleColor}`.
- **Acesso e regras:** Dono ou moderador. Personalização pode existir para quem já participou desse mesmo grupo.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 36. `POST /api/groups/{group}/join-requests/{id}/approve`

Aprova a solicitação e admite a pessoa no grupo.

- **Entrada:** `id`: UUID da solicitação; sem corpo.
- **Resposta:** 200; sem corpo.
- **Acesso e regras:** Dono ou moderador; solicitação deve pertencer ao grupo e estar pendente.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 37. `POST /api/groups/{group}/join-requests/{id}/decline`

Recusa a solicitação de entrada.

- **Entrada:** `id`: UUID da solicitação; sem corpo.
- **Resposta:** 200; sem corpo.
- **Acesso e regras:** Dono ou moderador; responder solicitação já resolvida gera conflito.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

## Java — Resultado, contestação e encerramento

### 38. `GET /api/bets/{bet}/resolution`

Alimenta o painel de resultado, prazos, permissões e eventual crédito recebido. Também avança pendências elegíveis antes de responder.

- **Entrada:** `bet`: UUID; sem corpo.
- **Resposta:** 200; `Resolution`.
- **Acesso e regras:** Integrante atual. A consulta pode disparar cancelamento por prazo, retirada de contestações de quem saiu ou conclusão financeira quando as condições forem atendidas.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 39. `POST /api/bets/{bet}/result`

Publica a opção vencedora e abre o prazo de contestação.

- **Entrada:** `{"optionId":"UUID_DA_OPCAO"}`.
- **Resposta:** 200; `Resolution`.
- **Acesso e regras:** Somente o autor, depois do término e até o limite de apuração. Opção deve ser da aposta.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 40. `POST /api/bets/{bet}/contests`

Registra discordância com o resultado publicado.

- **Entrada:** `bet`: UUID; sem corpo.
- **Resposta:** 200; `Resolution`.
- **Acesso e regras:** Integrante atual que confirmou palpite, dentro do prazo e sem contestação pendente própria. Mantém os pontos reservados até a decisão/conclusão.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 41. `POST /api/bets/{bet}/decision`

Permite ao dono resolver contestações mantendo, alterando ou cancelando o resultado.

- **Entrada:** `{"action":"KEEP"}`; `{"action":"CHANGE","optionId":"UUID"}`; ou `{"action":"CANCEL"}`.
- **Resposta:** 200; `Resolution`.
- **Acesso e regras:** Dono e contestação pendente. Apenas CHANGE recebe optionId. Uma decisão antecipada não reduz o prazo de contestação nem paga imediatamente os participantes.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 42. `POST /api/bets/{bet}/cancel`

Cancela a proposta durante o período inicial e devolve integralmente as reservas.

- **Entrada:** `bet`: UUID; sem corpo.
- **Resposta:** 200; `Resolution`.
- **Acesso e regras:** Somente o autor, nos primeiros 10% da duração, com aposta ainda aberta. Não é a mesma ação que ocultar uma aposta.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 43. `POST /api/bets/{bet}/delete`

Remove a aposta da interface preservando o histórico financeiro.

- **Entrada:** `bet`: UUID; sem corpo.
- **Resposta:** 200; sem corpo.
- **Acesso e regras:** Somente o dono. Se pendente, cancela e devolve reservas; se concluída, apenas oculta e não desfaz pagamentos.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

## Java — Personalizações por pontos

### 44. `GET /api/groups/{group}/personalization`

Carrega os preços e a aparência do próprio integrante no grupo.

- **Entrada:** `group`: UUID; sem corpo.
- **Resposta:** 200; `{prices:{nameColor,title,titleColor},style:{nameColor,title,titleColor}}`. Preços são strings decimais ou null; style.title é texto.
- **Acesso e regras:** Integrante atual. Preços null significam que o dono ainda não configurou as compras.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 45. `POST /api/groups/{group}/personalization/prices`

Define os três preços independentes das personalizações.

- **Entrada:** `{"nameColor":10,"title":20,"titleColor":5}`.
- **Resposta:** 200; sem corpo.
- **Acesso e regras:** Somente o dono. Valores não negativos; zero permite compra gratuita. Alterações valem para compras futuras.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 46. `POST /api/groups/{group}/personalization/purchases`

Compra uma mudança de cor do nome, texto do título ou cor do título.

- **Entrada:** `{"action":"NAME_COLOR","value":"#123456","clientId":"UUID","expectedPrice":"10"}`. Ações: NAME_COLOR, TITLE e TITLE_COLOR.
- **Resposta:** 200; sem corpo.
- **Acesso e regras:** Integrante atual, preço vigente confirmado e saldo disponível. Título: 1–30 caracteres, sem emojis; cor: #RRGGBB. Cor do título exige título. Mesmo clientId e conteúdo não cobra novamente; retirar título depois não é desfeito por repetição da compra.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 47. `POST /api/groups/{group}/members/{user}/title/remove`

Retira o título, restaura sua cor padrão e mantém a cor do nome.

- **Entrada:** `user`: UUID do titular; sem corpo.
- **Resposta:** 200; sem corpo.
- **Acesso e regras:** Próprio titular, dono para qualquer integrante, ou moderador para membro comum. Nenhuma remoção devolve pontos.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

## Python — Consultas e agregados

### 48. `GET /health`

Verifica se o Flask está respondendo. Usado pelo supervisor e pelo status do site.

- **Entrada:** Sem corpo ou autenticação.
- **Resposta:** 200; `{"status":"UP"}`.
- **Acesso e regras:** Não abre conexão com PostgreSQL; não comprova sozinho a disponibilidade dos dados.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 49. `GET /api/groups/{group_id}/ranking`

Alimenta ranking, destaque dos três primeiros e diferença de pontos para o integrante acima.

- **Entrada:** `group_id`: UUID; sem corpo.
- **Resposta:** 200; `{groupId,ranking:[{userId,displayName,availablePoints,position,nameColor,title,titleColor}]}`.
- **Acesso e regras:** Integrante atual. Ordena pelo saldo disponível e desempata pela criação da conta. availablePoints é string decimal; pontos reservados ficam fora.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 50. `GET /api/groups/{group_id}/participation`

Alimenta as barras das listagens do grupo e do Feed em uma consulta por grupo.

- **Entrada:** `group_id`: UUID; sem corpo.
- **Resposta:** 200; `{groupId,bets:[{betId,totalVotes,options:[{optionId,label,position,votes}]}]}`.
- **Acesso e regras:** Integrante atual. Somente contagens agregadas, sem escolhas ou valores individuais. Grupo sem apostas retorna bets vazio; aposta sem votos contém contagens zero.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

### 51. `GET /api/bets/{bet_id}/odds`

Mostra odds decimais e contagens do detalhe da aposta; permite acompanhar mudanças por long polling.

- **Entrada:** `bet_id`: UUID; cabeçalho opcional `X-Odds-Version` com a versão anterior; sem corpo.
- **Resposta:** 200; `{version,betId,groupId,totalVotes,options:[{optionId,label,position,votes,decimalOdds}]}`.
- **Acesso e regras:** Integrante atual do grupo. decimalOdds é string com duas casas ou null sem pontos na opção. Mesma versão pode aguardar até 15 s, consultando mudanças a cada 500 ms e revalidando acesso.
- **Verificação:** HTTP 200 recebido no ambiente isolado.

## Execução da verificação

A auditoria faz requisições HTTP reais para todos os caminhos, com usuários e
grupos fictícios em um schema PostgreSQL `gamblr_it_*`, criado e removido pelo
teste. Os JWTs de teste são assinados localmente; não usa senhas de pessoas
nem modifica grupos do schema de uso da aplicação. Spring e Flask escutam
em portas locais temporárias. Operações de escrita são exercitadas como parte
de um fluxo com pré-condições válidas, incluindo papéis, amizade, convites,
solicitações, saldo, prazo de resultado e contestação.

Para reproduzir, configure as credenciais de integração do README e execute:

```powershell
.\.venv\Scripts\python.exe scripts/writer.py integration --tests '*EndpointCatalogTest.accessEveryEndpointThroughHttp'
```

Implementação: [auditoria Java](../services/writer/src/test/java/com/gamblr/writer/EndpointCatalogTest.java)
e [auditoria Python](../services/reader/tests/endpoint_catalog_scenario.py).
Os registros locais em `.tools/endpoint-audit/java.json` e `python.json`
contêm método, caminho normalizado e status; não armazenam tokens ou senhas.
O relatório JUnit fica em `services/writer/build/test-results/test/`.

O teste confirma acesso bem-sucedido a cada rota; não é uma prova exaustiva de
todos os erros e permissões possíveis. As validações e regras descritas foram
conferidas também nos serviços, DTOs e testes já existentes do projeto.
Rotas auxiliares do servidor do frontend (`/config`, `/status` e `/assets/`)
não fazem parte dos 51 endpoints de backend deste documento.
