# Frontend

Interface local em HTML/CSS/JavaScript, com servidor/proxy Python. Não há
build obrigatório; o servidor entrega os arquivos de `frontend/static`.

## Iniciar

As migrações V001–V011 devem estar aplicadas, incluindo odds por pontos (V010)
e personalização/ranking (V011). Siga a [primeira instalação](../README.md#primeira-instalação).
Use o `.env` já configurado e contas confirmadas no Supabase Auth.

No Windows/PowerShell, para iniciar os três serviços na raiz:

```powershell
.\iniciar.cmd
```

Aguarde a mensagem de serviços prontos e abra **http://127.0.0.1:5173**. Ctrl+C
encerra os processos iniciados pelo comando. Confira [execucao-local.md](execucao-local.md)
para opções e tratamento de porta ocupada.

Para depurar os serviços separadamente, use os comandos abaixo.

Mantenha os dois backends em execução, em terminais separados na raiz do projeto:

```powershell
.\.venv\Scripts\python.exe scripts/writer.py bootRun
```

```powershell
.\.venv\Scripts\python.exe scripts/reader.py run
```

Em outro terminal, também na raiz:

```powershell
.\.venv\Scripts\python.exe scripts/frontend.py
```

Abra **http://127.0.0.1:5173**. Para outra porta, use `--port 5174`. O frontend
reutiliza as dependências Python já instaladas para os serviços. Não precisa de
npm nem compilação JavaScript para executar.

## Uso da interface

A navegação reúne grupos, Feed e exploração de grupos. A busca filtra grupos
pelo nome. Conversas e apostas ocupam a área principal.

O centro permite enviar mensagens no grupo selecionado ou para um amigo após
o aceite da amizade. A área privada não exibe apostas. A coluna do grupo começa
com o filtro **Ativas**, permite publicar e confirmar palpites e oferece o ranking
em uma seção expansível. Consulte o [roteiro dos chats](chats.md) para testar
pedidos de amizade, privacidade do histórico e moderação.
O [roteiro de resultados](resultados.md) cobre publicação do vencedor,
contestação, decisão do dono, cancelamento e pagamento automático.

1. Entre com a conta do autor.
2. Clique em **Criar grupo** e informe nome e pontos iniciais. A interface cria
   grupos públicos ou privados, com entrada imediata, solicitação ou convite.
   Pontos diários e prazos ficam no formulário expansível.
3. Clique em **Nova aposta** e informe pergunta, tipo, término e mínimo. Múltipla
   escolha aceita uma opção por linha. O critério de verificação é opcional.
4. Clique em **Outra sessão**. A nova aba começa sem a sessão da anterior.
5. Entre com outra conta, encontre o grupo em **Explorar grupos** e clique em
   **Entrar no grupo**. Não é necessário copiar IDs.
6. Abra a aposta, escolha uma opção e confirme o valor. Com saldo inicial 100 e
   palpite de 30, a tela mostra **70 disponíveis e 30 reservados**.
7. Acompanhe ranking e odds nas duas abas. O ranking é consultado periodicamente;
   odds usam long polling (até 15 segundos por consulta, verificações a cada 500 ms
   no reader). Novas consultas pausam em abas ocultas. O botão ↻ atualiza imediatamente.

O autor não pode confirmar palpites na própria aposta. Após confirmar, o
participante vê sua escolha/valor e os controles ficam bloqueados. Os demais
veem somente votos/odds agregados. A interface exibe dados reais das APIs, sem
inserir grupos, saldos ou palpites fictícios automaticamente.

## Login e sessões

Login e cadastro usam Supabase Auth. Com confirmação de email habilitada, o
cadastro orienta conferir a mensagem antes de entrar. SMTP e URLs de confirmação
dependem da configuração do projeto, conforme [supabase-auth.md](supabase-auth.md).
Para testar imediatamente, use as contas já confirmadas.

Tokens ficam em `sessionStorage`, separados por aba, permitindo recarregar sem
repetir o login. O cliente renova a sessão quando necessário. Senhas não são
armazenadas. **Sair** limpa a sessão local e solicita encerrar somente aquela
sessão no Auth, preservando a outra conta em teste.

A interface não carrega JavaScript externo por CDN. Campos de usuários são inseridos como
texto. Arquivos são servidos com política de conteúdo restrita.

## Serviços e configuração pública

O navegador usa `/writer/...` e `/reader/...` no mesmo endereço do frontend. O
proxy encaminha somente rotas permitidas aos backends locais, preservando o token
de usuário. A autorização continua no Spring/Flask. Não é necessário abrir CORS
nos dois serviços para esta interface.

`/config` entrega somente a Project URL e a chave publishable pública. Senhas de
banco, credenciais administrativas e chaves `service_role` não são enviadas ao
navegador. Os destinos do proxy são portas locais do `.env`, sem proxy para URLs
arbitrárias. A interface não consulta as tabelas diretamente pelo Supabase.

Para acessar de outro computador na mesma rede, configure
`GAMBLR_BIND_ADDRESS=0.0.0.0`, reinicie os serviços necessários e abra
`http://IP_DA_MAQUINA_ANFITRIA:5173`. O acesso depende também do firewall local.
O proxy continua acessando os backends da máquina anfitriã.

## Consultas do Spring usadas na navegação

- `GET /api/groups`: grupos em que o usuário autenticado é integrante atual.
- `GET /api/groups/public`: grupos públicos disponíveis para exploração.
- `GET /api/groups/{id}/bets`: propostas do grupo e somente o palpite do próprio
  usuário (`myPrediction`), quando houver.

Os detalhes da proposta incluem estado e critério de verificação. A listagem de
apostas exige participação atual. Grupos privados não aparecem na exploração.

## Apostas, ranking e estados vazios (RF-41 a RF-43)

Os cartões do grupo e do Feed destacam a pergunta e diferenciam seis situações
com rótulos e cores suaves. As barras usam quantidades de palpites por opção,
com contagem e percentuais em texto; não representam pontos ou probabilidades.
Uma aposta sem palpites usa uma barra neutra. Carregamento e indisponibilidade
têm mensagens próprias, sem representar falha como zero participantes.

O ranking permanece ordenado pelo servidor, conforme RF-20. Os três primeiros
recebem marcadores e fundos distintos, preservando as cores de nome e título.
A mensagem pessoal mostra posição, liderança, empate ou diferença de saldo
disponível para o integrante imediatamente acima. O cálculo da diferença usa
aritmética decimal exata, inclusive para valores fracionários. Igualar o saldo
não modifica o desempate por criação da conta.

Grupos, apostas, Feed, chats e ranking usam pequenos desenhos SVG e orientação
contextual nos estados vazios. As ações abrem os fluxos existentes de criação,
publicação, convite e escrita, ou limpam filtros. O convite só é oferecido
quando a configuração atual permite convidar. Falhas apresentam uma ação de
nova tentativa e não são anunciadas como ausência de conteúdo.

O Flask oferece `GET /api/groups/{id}/participation`, autorizado por participação
atual no grupo. Uma consulta à view `bet_odds` reúne os agregados de todas as
apostas daquele grupo, sem retornar escolhas, valores ou identidades individuais.
O proxy local encaminha essa rota apenas ao reader, preservando o Bearer token.
Grupo e Feed atualizam dados a cada cinco segundos enquanto a aba está visível.
Os cartões encerrados identificam a participação como encerrada. A consulta de
odds do detalhe mantém seu mecanismo existente de atualização.

## Aparência e responsividade

A interface usa identidade azul, fonte Arial, sombras discretas, fundos suaves,
balões de mensagem e indicação de foco nos controles. A folha única
[style.css](../frontend/static/style.css) reúne estilos e regras responsivas,
sem dependências adicionais de UI.

A organização lateral é inspirada no Facebook de 2012 e utiliza como referência a
[imagem de organização lateral](https://img.odcdn.com.br/wp-content/uploads/2020/08/20200821092335.jpg).

- Acima de 1100 px: menu à esquerda, conversa central, apostas e contatos à direita.
- De 821 a 1100 px: menu e contatos permanecem nas laterais; apostas ficam abaixo
  da conversa para preservar a largura de leitura.
- Até 820 px: amigos ficam no menu; grupos e amigos aparecem acima da conversa.

As laterais em telas maiores ficam fixas durante a rolagem abaixo do cabeçalho,
com rolagem interna quando necessário. [sidebars.js](../frontend/static/sidebars.js)
reposiciona os mesmos elementos, preservando eventos, estado e foco de teclado.

O chat aproxima mensagens adjacentes do mesmo autor enviadas em até cinco minutos
no mesmo dia. Mensagens apagadas e eventos de apostas interrompem o agrupamento.
Cada mensagem mantém conteúdo, autor, horário e ações. A regra está em
[message-layout.js](../frontend/static/message-layout.js). Cores de nome e título
escolhidas pelos integrantes são preservadas.

## Funcionalidades relacionadas

Veja [administração](administracao.md) para convites, saída/remoção e crédito diário;
[resultados](resultados.md) para apuração, contestação e pagamento; e
[personalização](personalizacao.md) para cores e títulos por pontos. Pontos são
exclusivamente virtuais, sem compra ou saque.

## Testes

Windows/PowerShell, na raiz. A integração exige banco isolado e as quatro
credenciais com CA; veja [testes e limites](../README.md#testes-e-limites).

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
npm.cmd --prefix frontend test
.\.venv\Scripts\python.exe scripts/writer.py integration
```

Para o navegador, instale o Microsoft Edge (canal `msedge` na configuração
do Playwright). Com o frontend em execução, parta da raiz no PowerShell:

```powershell
cd frontend
npm.cmd ci
.\node_modules\.bin\playwright.cmd test
```

O teste usa Edge headless e respostas simuladas para testar duas sessões,
amizades, chats, moderação, formulários, recarga, filtros e layout móvel. As imagens ficam em
`frontend/test-results`, ignorado pelo Git. Os testes Java usam PostgreSQL real
em schema temporário para verificar listagens, reservas e privacidade. Os testes
do proxy conferem restrição de rotas e ausência de credenciais privadas.


Os cenários `frontend/e2e/appearance.spec.js` e `frontend/e2e/sidebars.spec.js`
cobrem aparência, colunas, foco e preservação de rascunhos ao redimensionar.
`frontend/tests/message-layout.test.js` cobre os limites do agrupamento de mensagens.
