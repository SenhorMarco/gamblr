# Frontend temporário

Interface local em HTML/CSS/JavaScript, com servidor/proxy Python. A escolha da
arquitetura definitiva do frontend continua em aberto. Não há hospedagem externa
nem build obrigatório para usar este protótipo.

## Iniciar

As migrações até V006 devem estar aplicadas. Não há nova migração nesta entrega.
Use o `.env` já configurado e contas confirmadas no Supabase Auth.

Mantenha os dois backends em execução, em terminais separados na raiz do projeto:

```powershell
.\.venv\Scripts\python.exe scripts/writer.py bootRun
```

```powershell
.\.venv\Scripts\python.exe scripts/reader.py run
```

Uma instância antiga do Spring precisa ser reiniciada para carregar as novas
rotas de listagem de grupos/apostas. Não inicie duas instâncias na mesma porta.

Em outro terminal, também na raiz:

```powershell
.\.venv\Scripts\python.exe scripts/frontend.py
```

Abra **http://127.0.0.1:5173**. Para outra porta, use `--port 5174`. O frontend
reutiliza as dependências Python já instaladas para os serviços. Não precisa de
npm nem compilação JavaScript para executar.

## Roteiro pelo navegador

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

Ainda não há chat, convites, administração, remoção de integrantes, crédito diário
automático, cancelamento, publicação/contestação de resultado ou pagamento. A tela
informa que o fluxo termina na reserva. Os prazos de apuração/contestação são
persistidos para a implementação posterior. Pontos são exclusivamente virtuais.

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
formulários, recarga, filtros e layout móvel. As imagens ficam em
`frontend/test-results`, ignorado pelo Git. Os testes Java usam PostgreSQL real
em schema temporário para verificar listagens, reservas e privacidade. Os testes
do proxy conferem restrição de rotas e ausência de credenciais privadas.
