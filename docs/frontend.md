# Frontend temporário

Interface local em HTML/CSS/JavaScript, com servidor/proxy Python. A escolha da
arquitetura definitiva do frontend continua em aberto. Não há hospedagem externa
nem build obrigatório para usar este protótipo.

## Iniciar

As migrações até V008 devem estar aplicadas. Para resultado e pagamento, execute
[V008](../database/migrations/V008__bet_resolution.sql) no SQL Editor após V007.
Use o `.env` já configurado e contas confirmadas no Supabase Auth.

Para iniciar os três serviços em um único terminal, na raiz:

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

Reinicie o Spring e o servidor do frontend para carregar as rotas de chats e
amizades. Não inicie duas instâncias na mesma porta. O Flask de ranking e odds
não precisa ser alterado.

Em outro terminal, também na raiz:

```powershell
.\.venv\Scripts\python.exe scripts/frontend.py
```

Abra **http://127.0.0.1:5173**. Para outra porta, use `--port 5174`. O frontend
reutiliza as dependências Python já instaladas para os serviços. Não precisa de
npm nem compilação JavaScript para executar.

## Roteiro pelo navegador

A interface usa uma barra azul no topo e três colunas, inspiradas no Facebook
de 2012: grupos e amigos à esquerda, conversa selecionada ao centro e apostas do
grupo à direita. A busca filtra os grupos pelo nome. No celular, as colunas se
empilham para preservar a leitura e os controles.

O centro permite enviar mensagens no grupo selecionado ou para um amigo após
o aceite da amizade. A área privada não exibe apostas. A coluna do grupo começa
com o filtro **Ativas**, permite publicar e confirmar palpites e oferece o ranking
em uma seção expansível. Consulte o [roteiro dos chats](chats.md) para testar
pedidos de amizade, privacidade do histórico e moderação.
O [roteiro de resultados](resultados.md) cobre publicação do vencedor,
contestação, decisão do dono, cancelamento e pagamento automático.

1. Entre com a conta do autor.
2. Clique em **Criar grupo** e informe nome e pontos iniciais. A interface cria
   grupos públicos com entrada imediata. Pontos e prazos adicionais ficam no
   formulário expansível; o crédito diário automático ainda não está ativo.
3. Clique em **Nova aposta** e informe pergunta, tipo, término e mínimo. Múltipla
   escolha aceita uma opção por linha. O critério de verificação é opcional.
4. Clique em **Outra sessão**. A nova aba começa sem a sessão da anterior.
5. Entre com outra conta, encontre o grupo em **Explorar grupos** e clique em
   **Entrar no grupo**. Não é necessário copiar IDs.
6. Abra a aposta, escolha uma opção e confirme o valor. Com saldo inicial 100 e
   palpite de 30, a tela mostra **70 disponíveis e 30 reservados**.
7. Acompanhe ranking e odds nas duas abas. As consultas atualizam a cada 5 segundos
   enquanto a aba está visível. O botão ↻ atualiza imediatamente.

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

Não carregamos JavaScript externo por CDN. Campos de usuários são inseridos como
texto. Arquivos são servidos com política de conteúdo restrita. O tratamento
definitivo de sessões pode ser revisto com a arquitetura de produção.

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

## Novas rotas do Spring

- `GET /api/groups`: grupos em que o usuário autenticado é integrante atual.
- `GET /api/groups/public`: grupos públicos com entrada imediata.
- `GET /api/groups/{id}/bets`: propostas do grupo e somente o palpite do próprio
  usuário (`myPrediction`), quando houver.

Os detalhes da proposta incluem estado e critério de verificação. A listagem de
apostas exige participação atual. Grupos privados não aparecem na exploração.

## Limites

Ainda não há convites, gestão de moderadores, saída/remoção de integrantes ou
crédito diário automático. Resultado, contestação, cancelamento e pagamento
estão implementados conforme [resultados.md](resultados.md). Pontos são
exclusivamente virtuais.

## Testes

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
npm.cmd --prefix frontend test
.\.venv\Scripts\python.exe scripts/writer.py integration
```

Para o navegador, com o frontend em execução:

```powershell
cd frontend
npm.cmd install
.\node_modules\.bin\playwright.cmd test
```

O teste usa Edge headless e respostas simuladas para testar duas sessões,
amizades, chats, moderação, formulários, recarga, filtros e layout móvel. As imagens ficam em
`frontend/test-results`, ignorado pelo Git. Os testes Java usam PostgreSQL real
em schema temporário para verificar listagens, reservas e privacidade. Os testes
do proxy conferem restrição de rotas e ausência de credenciais privadas.
