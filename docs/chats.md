# Chats e amizades

Mensagens persistem no Supabase e passam pelo Spring Boot, com a identidade
obtida do JWT. O Flask continua responsável por ranking e odds. O frontend usa
o mesmo proxy local e não acessa diretamente as tabelas de mensagens.

## Ativar no ambiente existente

1. No SQL Editor do Supabase, como `postgres`, execute todo o conteúdo de
   [V007__friendships_messages.sql](../database/migrations/V007__friendships_messages.sql),
   incluindo `BEGIN`, `SET LOCAL ROLE` e `COMMIT`. As migrações V001–V006 devem
   estar aplicadas. Não execute novamente as migrações anteriores.
2. A V007 já habilita RLS nas três tabelas novas. As policies são exclusivas para
   a conta técnica `gamblr_writer`; `anon`, `authenticated` e o reader não recebem
   acesso. Mantenha o schema `gamblr` fora da Data API. A autorização por pessoa
   e conversa é feita no Spring, que usa uma conta compartilhada de servidor.
3. Pare e reinicie o Spring e o frontend nos respectivos terminais:

   ```powershell
   .\.venv\Scripts\python.exe scripts/writer.py bootRun
   ```

   ```powershell
   .\.venv\Scripts\python.exe scripts/frontend.py
   ```

4. Recarregue a página. O serviço Flask de ranking/odds pode continuar em execução.

Não há novas variáveis de ambiente ou dependências. A migração é aplicada
manualmente, como as anteriores; os testes não a aplicam ao schema do projeto.

## Testar com duas contas

1. Entre com o autor do grupo e envie uma mensagem pelo centro da tela.
2. Abra **Outra sessão**, entre com uma segunda conta e entre no grupo. A mensagem
   anterior à entrada não deve aparecer. Apostas abertas continuam disponíveis.
3. Envie novas mensagens nas duas sessões. Enter envia; Shift+Enter insere uma
   quebra de linha. O texto é limitado a 4.000 caracteres e renderizado como texto.
4. Na sessão do dono, use **Apagar** em uma mensagem. O corpo deixa de ser exibido
   para os integrantes. Membros comuns não podem apagar, nem chamando a API.
5. No **+** ao lado de **AMIGOS**, busque o nome da segunda pessoa e envie o pedido.
   A pessoa deve ter entrado no Gamblr ao menos uma vez para possuir perfil interno.
   A busca retorna até 20 perfis, com nome e código; não expõe emails.
6. Na segunda sessão, aceite o pedido na coluna esquerda. Apenas o destinatário
   pode aceitar ou recusar. Um pedido cruzado não aceita automaticamente a amizade.
7. Clique no amigo e troque mensagens privadas. A coluna de apostas fica oculta;
   uma terceira conta não tem acesso a essa conversa.
8. Recarregue para conferir a persistência. Na rede local, utilize o endereço da
   máquina anfitriã, como descrito em [frontend.md](frontend.md).

## Contratos e escolhas do protótipo

- RF-31: leitura e envio exigem participação atual. O filtro usa `joined_at`
  da entrada atual; uma nova entrada estabelece um novo limite de histórico.
  `first_joined_at` continua preservado para a sucessão prevista no produto.
- Entrada e envio de grupo coordenam locks no grupo. As datas usam o relógio do
  banco após os locks, evitando revelar mensagens anteriores à entrada quando
  uma transação aguardou outra. Saída/remoção bloqueia o acesso ao chat.
- RF-18: dono e moderadores atuais podem apagar mensagens do grupo visíveis
  para eles. A exclusão é lógica: o corpo é omitido da API e aparece um aviso
  de moderação. Gestão de moderadores e remoção são acrescentadas pela V009,
  conforme [administracao.md](administracao.md).
- RF-16: chat privado exige amizade aceita em todas as leituras e escritas.
  Pedidos recusados não aparecem na lista e podem ser enviados novamente.
- RF-32: não existem endpoints de apostas em conversas privadas.
- Mensagens e amizades atualizam a cada 3 segundos na aba visível. Não há
  WebSocket, presença online, anexos, edição, indicadores de leitura ou notificações
  do sistema operacional nesta etapa.
- Cada página contém até 100 mensagens em ordem cronológica. **Mensagens
  anteriores** abre a página anterior; **Voltar às recentes** acompanha as novas.
  Enquanto uma página antiga está aberta, a atualização permanece nessa página.
- O cliente gera um `clientId` por envio. Repetir a mesma chave, autor, conversa
  e conteúdo não duplica a mensagem. Uma chave com conteúdo diferente retorna 409.
  Após falha de rede, repetir o mesmo texto na mesma conversa reutiliza essa chave
  enquanto a página estiver aberta; uma recarga descarta essa tentativa pendente.

Esses limites são escolhas técnicas do protótipo. As decisões de produto ainda
em aberto continuam no documento de requisitos.

## Endpoints autenticados do Spring

| Método | Rota | Uso |
| --- | --- | --- |
| GET | `/api/users?name=...` | Buscar nomes (2–80 caracteres) |
| GET / POST | `/api/friendships` | Listar amigos/pedidos; solicitar com `userId` |
| POST | `/api/friendships/{id}/accept` | Aceitar pedido recebido |
| POST | `/api/friendships/{id}/decline` | Recusar pedido recebido |
| GET / POST | `/api/groups/{id}/messages` | Ler/enviar no grupo |
| POST | `/api/groups/{id}/messages/{messageId}/delete` | Apagar pela moderação |
| GET / POST | `/api/friends/{userId}/messages` | Ler/enviar entre amigos |

Os GETs de mensagens aceitam `before`, um ID positivo exclusivo para paginação.
Retornam `messages`, `nextBefore` e `canModerate`. Os POSTs de mensagens recebem
`body` e `clientId` (UUID) e retornam 204. O servidor define autor, data e conversa;
não aceita esses valores como identificação fornecida pelo cliente.

## Verificação

Os testes de integração Java criam um schema temporário com V001–V007 e o
removem ao terminar. Exercitam histórico anterior à entrada, retorno ao grupo,
paginação, exclusão por moderação, replay de envio, aceite explícito e rejeição
de terceiros. Conferem também que a conta do Flask não consegue ler mensagens.
O teste de navegador exercita duas sessões com respostas simuladas.

```powershell
.\.venv\Scripts\python.exe scripts/writer.py integration
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
```

Com o frontend em execução, na pasta `frontend`:

```powershell
.\node_modules\.bin\playwright.cmd test
```
