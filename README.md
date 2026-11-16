# Gamblr

Plataforma de apostas em grupos com **pontos virtuais**, sem compra ou saque.
Permite criar grupos, publicar apostas, reservar palpites, acompanhar ranking e
odds, conversar e apurar resultados. Integrantes: **Ferreira, Brasil e Kossooski**.

Java/Spring Boot cuida das escritas e regras do domínio; Python/Flask consulta
ranking, participação e odds. O frontend HTML/CSS/JavaScript consome as duas APIs
por um proxy Python de mesma origem. PostgreSQL e autenticação por email/senha
ficam no **Supabase**. Os três processos rodam localmente, mas precisam de internet
para banco e Auth; o projeto não funciona offline.

## Primeira instalação

### 1. Ferramentas e checkout

Use Git, **JDK 21** (não apenas JRE), **Python 3.13** com `venv`/pip e um navegador
atual. Essas versões foram usadas na verificação local. O Java compila com
`release=21`; não há matriz de suporte para outros JDKs/Pythons no projeto.
O Gradle Wrapper **9.9.1** baixa o Gradle automaticamente; Spring Boot é **4.1.2**.
Confira `java -version`, `javac -version` e `python --version`; `JAVA_HOME`, se
presente, deve apontar para o JDK. No Linux use `python3` com suporte a `venv`.

No Windows, prefira uma instalação convencional de [Python.org](https://www.python.org/downloads/windows/).
Na edição Microsoft Store 3.13.14 deste ambiente, dois testes de encerramento do
supervisor falharam: descendentes permaneceram ativos. Não considere o Ctrl+C do
comando único validado nessa edição. A mesma suíte passou no Python portátil
oficial 3.13.14; veja ambiente exato e limitações em [verificação](docs/verificacao.md).

Node.js é necessário somente para testes (verificados com **24.14.1**), não para
servir a página. Não há build JavaScript obrigatório. Dependências Python: Flask
3.1.3, PyJWT com criptografia 2.15.1, pg8000 1.31.6 e python-dotenv 1.2.4, conforme
os dois arquivos de requisitos. O PostgreSQL é fornecido pelo Supabase; não há
versão local de PostgreSQL fixada no repositório.

Para um checkout novo:

```powershell
git clone --branch davi https://github.com/SenhorMarco/gamblr.git
cd gamblr
```

Em checkout existente, preserve alterações e configuração. Os comandos seguintes
partem da raiz do projeto.

### 2. Ambiente Python e dependências

Windows/PowerShell:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r scripts/requirements.txt -r services/reader/requirements.txt
```

Linux:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/requirements.txt -r services/reader/requirements.txt
```

Não recrie um ambiente já configurado sem necessidade. Uma `.venv` do Windows não
é reutilizável no Linux. Não é preciso ativá-la: os comandos usam seu interpretador
explicitamente. Instalar somente `scripts/requirements.txt` não instala Flask/PyJWT.

### 3. Configuração local e certificado

Crie um projeto Supabase de desenvolvimento dedicado. Em **Connect → Session
pooler**, obtenha host, porta, banco e usuário. Copie o modelo somente se não existir:

```powershell
if (-not (Test-Path -LiteralPath .env)) { Copy-Item -LiteralPath .env.example -Destination .env }
```

No Linux: `test -e .env || cp .env.example .env`. Preencha o arquivo local; nunca
envie senhas/tokens ao Git. Preserve valores existentes e use senhas distintas.

| Configuração | Preenchimento |
| --- | --- |
| `SUPABASE_DB_HOST`, `SUPABASE_DB_PORT`, `SUPABASE_DB_NAME` | Session pooler; normalmente porta 5432, banco `postgres` |
| `SUPABASE_DB_USER`, `SUPABASE_DB_PASSWORD` | Admin `postgres.PROJECT_REF`; preparação/verificadores, não execução dos serviços |
| `GAMBLR_MIGRATION_DB_USER`, `GAMBLR_MIGRATION_DB_PASSWORD` | `gamblr_migrator.PROJECT_REF` e senha própria |
| `GAMBLR_WRITE_DB_USER`, `GAMBLR_WRITE_DB_PASSWORD` | `gamblr_writer.PROJECT_REF` e senha própria; Java |
| `GAMBLR_READ_DB_USER`, `GAMBLR_READ_DB_PASSWORD` | `gamblr_reader.PROJECT_REF` e senha própria; Flask |
| `SUPABASE_DB_SSLMODE` | Use `verify-full`; os serviços verificam TLS e hostname |
| `SUPABASE_DB_SSLROOTCERT` | CA, por exemplo `certs/supabase-ca.crt`; obrigatório nos lançadores Java/Flask |
| `SUPABASE_URL` | Project URL HTTPS, sem `/auth/v1` ao final |
| `SUPABASE_PUBLISHABLE_KEY` | Publishable ou anon legada; nunca secret/service_role; exposta ao navegador |
| `GAMBLR_AUTH_ISSUER` | Deixe vazio; deriva de `SUPABASE_URL/auth/v1` |
| `GAMBLR_WRITER_PORT`, `GAMBLR_READER_PORT` | 8080 e 5000; devem ser diferentes |
| `GAMBLR_BIND_ADDRESS` | `127.0.0.1`; `0.0.0.0` somente para acesso pela rede local |

Baixe o CA do projeto em **Database Settings → SSL Configuration**, crie `certs/`
e salve o PEM. Os lançadores resolvem o caminho relativo a partir da raiz; com
Gradle/IDE diretamente, use caminho absoluto. `.env`, `certs/`, `.venv/`, `.tools/`
e builds são locais e ignorados. Os scripts Python leem `.env` sem expandir
`${...}`; `gradlew bootRun` sozinho não o carrega.

### 4. Banco e autenticação

Em projeto novo, no **SQL Editor**, como `postgres`:

1. Execute [bootstrap/001_roles.sql](database/bootstrap/001_roles.sql) completo.
   Cria as roles `gamblr_migrator`, `gamblr_writer`, `gamblr_reader` sem login e o
   schema privado `gamblr`.
2. Execute todos os arquivos de [database/migrations](database/migrations), um
   por consulta, na ordem **V001 → V002 → V003 → V004 → V005 → V006 → V007 → V008 →
   V009 → V010 → V011**. Cada arquivo controla sua transação e assume o migrador.
   Pare em caso de erro. Não repita migrações aplicadas nem altere seus arquivos.
   Registre as versões: não há executor/histórico automático de migrações.
3. Em consulta privada, habilite login e senhas próprias com
   `ALTER ROLE gamblr_migrator LOGIN PASSWORD 'SENHA_GERADA';` e o mesmo comando
   para `gamblr_writer` e `gamblr_reader`. Substitua o exemplo por senhas geradas;
   não salve a consulta preenchida no Git ou em consultas compartilhadas. Preencha
   as três credenciais no `.env` (no pooler, acrescente `.PROJECT_REF` ao usuário).
4. Mantenha `gamblr` fora dos schemas expostos pela Data API. Os serviços usam
   PostgreSQL diretamente; não conceda tabelas a `anon`/`authenticated`. O writer
   não recebe DDL; o reader lê apenas views explicitamente autorizadas.
5. No Auth, habilite Email/senha e assinatura **ES256 ou RS256**. Projeto legado
   somente HS256 precisa preparar a migração para chaves assimétricas. Configure
   Site URL/redirects para o endereço local se testar confirmação de email.
   Para smoke test, crie duas contas descartáveis confirmadas no projeto isolado,
   com senhas próprias; não desabilite a confirmação global para facilitar testes.

V005 vincula identidades; V006 autoriza consultas; V007 acrescenta chats; V008,
resultados/pagamentos; V009, administração/crédito diário; V010, odds por pontos;
V011, personalização e colunas exigidas pelo ranking atual. O código atual requer
**V011**, mesmo quando um guia de funcionalidade descreve uma migração anterior.
Detalhes: [banco](docs/banco-de-dados.md), [TLS/conexão](docs/supabase.md) e
[Auth](docs/supabase-auth.md). Não execute a instalação sobre banco compartilhado
existente sem revisar o estado e planejar a atualização.

### 5. Conferir configuração e compilar

```powershell
.\.venv\Scripts\python.exe scripts/check_supabase.py --all --model
.\.venv\Scripts\python.exe scripts/supabase_auth.py check
.\.venv\Scripts\python.exe scripts/reader.py check
.\.venv\Scripts\python.exe scripts/writer.py build
```

No Linux substitua `.\.venv\Scripts\python.exe` por `.venv/bin/python`. Os três
verificadores fazem leituras; não criam contas nem aplicam migrações. O build Java
não precisa de banco e ignora integração se `GAMBLR_IT` estiver ausente. Não defina
essa variável no build comum. O primeiro build baixa Gradle/dependências.

## Inicialização rotineira

Após instalar e configurar, execute `.\iniciar.cmd` no Windows ou `./iniciar.sh`
no Linux. Aguarde **Todos os servicos estao prontos** e abra **http://127.0.0.1:5173**.
Ctrl+C encerra os processos iniciados. Pare instâncias anteriores antes de iniciar.
Use `--timeout 600` para aumentar a espera, `--backend-only` para somente as APIs
ou `--port 5174` para trocar a porta do site. O comando não instala dependências
nem aplica migrações. Veja [execução local](docs/execucao-local.md).

Para iniciar separadamente, use um terminal por componente:

| Componente | Comando Windows na raiz | Porta |
| --- | --- | --- |
| Java | `.\.venv\Scripts\python.exe scripts/writer.py bootRun` | 8080 |
| Python | `.\.venv\Scripts\python.exe scripts/reader.py run` | 5000 |
| Frontend/proxy | `.\.venv\Scripts\python.exe scripts/frontend.py` | 5173 |

No Linux use `.venv/bin/python` nos mesmos comandos. O frontend é servido por
Flask, não por Vite, apesar da porta 5173.

### Prontidão e fluxo pelo navegador

No PowerShell, `Invoke-RestMethod http://127.0.0.1:8080/health` e
`Invoke-RestMethod http://127.0.0.1:5000/health` devem retornar status `UP`.
`Invoke-RestMethod http://127.0.0.1:5173/status` indica a disponibilidade HTTP dos
dois serviços. No Linux use `curl`. Esses endpoints não comprovam login, banco
ou regras de negócio.

Em ambiente isolado, com duas contas descartáveis:

1. Faça login; em Network, veja Auth no Supabase e `POST /writer/api/me` no Java.
2. Crie grupo público de entrada imediata com 100 pontos iniciais e publique
   aposta com término futuro. Essas ações chamam `/writer/api/...`.
3. Abra **Outra sessão**, entre com a segunda conta, acesse o grupo e confirme
   palpite de 30 pontos. Confira 70 disponíveis/30 reservados e a confirmação.
4. Abra ranking e odds. Network deve mostrar JSON de
   `/reader/api/groups/{group}/ranking`, `/reader/api/groups/{group}/participation`
   e `/reader/api/bets/{bet}/odds`. Com um único palpite, a opção escolhida tem
   odd `1.00`; a outra, `null`. Odds usam total de pontos/pontos da opção; votos
   medem pessoas. A atualização de odds usa long polling, não WebSocket.
5. Confira erros JSON sem interromper os serviços: consulta protegida sem token
   recebe 401; com sessão válida, aposta inexistente recebe 404 e entrada inválida
   é rejeitada. Não copie tokens para arquivos/logs. O [roteiro por terminal](docs/demonstracao.md)
   verifica também palpite duplicado e preservação do saldo.

## Endpoints

As tabelas listam as APIs diretas. Exceto `/health`, exigem
`Authorization: Bearer ACCESS_TOKEN` do Supabase Auth (ES256/RS256, issuer do
projeto, audience/role `authenticated`). Java resolve o perfil interno;
`POST /api/me` o provisiona. Demais operações exigem perfil e permissões de domínio.
“Membro” é integrante atual; prazos, estado e saldo também podem impedir operações.
Parâmetros entre chaves são UUIDs, salvo ID numérico de mensagem.

### Java — http://127.0.0.1:8080

Inventário dos cinco controllers em `services/writer/src/main/java/com/gamblr/writer`.

| Método | Caminho | Finalidade / autorização adicional |
| --- | --- | --- |
| GET | `/health` | Estado HTTP; público |
| POST | `/api/me` | Criar/recuperar perfil do JWT |
| POST | `/api/groups` | Criar grupo |
| GET | `/api/groups` | Meus grupos |
| GET | `/api/groups/public` | Descobrir grupos públicos |
| POST | `/api/groups/{group}/members/me` | Entrar em grupo público de entrada imediata |
| GET | `/api/groups/{group}/wallet/me` | Minha carteira; membro |
| POST | `/api/groups/{group}/bets` | Publicar; membro |
| GET | `/api/groups/{group}/bets` | Listar apostas; membro |
| GET | `/api/bets/{bet}` | Proposta; membro |
| POST | `/api/bets/{bet}/predictions` | Reservar palpite; membro diferente do autor |
| GET | `/api/bets/{bet}/prediction/me` | Próprio palpite; membro |
| GET | `/api/users?name=...` | Buscar pessoas pelo nome |
| GET | `/api/friendships` | Minhas amizades/pedidos |
| POST | `/api/friendships` | Solicitar amizade |
| POST | `/api/friendships/{id}/accept` | Aceitar; destinatário |
| POST | `/api/friendships/{id}/decline` | Recusar; destinatário |
| GET | `/api/groups/{group}/messages` | Histórico desde entrada; membro; `before` opcional |
| POST | `/api/groups/{group}/messages` | Enviar; membro |
| POST | `/api/groups/{group}/messages/{id}/delete` | Apagar; dono/moderador |
| GET | `/api/friends/{friend}/messages` | Histórico privado; amizade aceita; `before` opcional |
| POST | `/api/friends/{friend}/messages` | Enviar mensagem; amizade aceita |
| GET | `/api/groups/{group}/settings` | Configuração; membro |
| POST | `/api/groups/{group}/settings` | Configurar; dono |
| GET | `/api/groups/{group}/members` | Integrantes; membro |
| POST | `/api/groups/{group}/members/{user}/role` | Alterar moderação; dono |
| POST | `/api/groups/{group}/members/{user}/remove` | Remover; dono ou moderador sobre membro comum |
| POST | `/api/groups/{group}/leave` | Sair; membro; aplica sucessão |
| POST | `/api/groups/{group}/transfer` | Transferir propriedade; dono |
| POST | `/api/groups/{group}/invitations` | Convidar; membro autorizado pela política do grupo |
| GET | `/api/group-invitations` | Meus convites pendentes |
| POST | `/api/group-invitations/{id}/accept` | Aceitar convite; destinatário |
| POST | `/api/group-invitations/{id}/decline` | Recusar convite; destinatário |
| POST | `/api/groups/{group}/join-requests` | Solicitar entrada pública com aprovação |
| GET | `/api/groups/{group}/join-requests` | Pedidos; dono/moderador |
| POST | `/api/groups/{group}/join-requests/{id}/approve` | Aprovar; dono/moderador |
| POST | `/api/groups/{group}/join-requests/{id}/decline` | Recusar; dono/moderador |
| GET | `/api/groups/{group}/personalization` | Estilo/preços; membro |
| POST | `/api/groups/{group}/personalization/prices` | Configurar preços; dono |
| POST | `/api/groups/{group}/personalization/purchases` | Comprar personalização; membro |
| POST | `/api/groups/{group}/members/{user}/title/remove` | Remover título próprio ou moderação permitida |
| GET | `/api/bets/{bet}/resolution` | Apuração; membro |
| POST | `/api/bets/{bet}/result` | Publicar resultado; autor membro, no prazo |
| POST | `/api/bets/{bet}/contests` | Contestar; membro com palpite |
| POST | `/api/bets/{bet}/decision` | Decidir contestações; dono |
| POST | `/api/bets/{bet}/cancel` | Cancelar; autor, primeiros 10% da duração |
| POST | `/api/bets/{bet}/delete` | Excluir aposta; dono |

Corpos/regras: [writer](docs/writer.md), [chats](docs/chats.md),
[administração](docs/administracao.md), [resultados](docs/resultados.md) e
[personalização](docs/personalizacao.md). Algumas escritas retornam corpo vazio;
consultas retornam JSON e erros das APIs usam `code`/`message`.

### Python — http://127.0.0.1:5000

Inventário de `services/reader/gamblr_reader/__init__.py`:

| Método | Caminho | Finalidade / autorização adicional |
| --- | --- | --- |
| GET | `/health` | Estado HTTP, sem banco; público |
| GET | `/api/groups/{group}/ranking` | Ranking por saldo disponível; membro |
| GET | `/api/groups/{group}/participation` | Votos agregados das apostas do grupo; membro |
| GET | `/api/bets/{bet}/odds` | Odds por pontos e votos; membro; `X-Odds-Version` opcional para long polling |

O reader não cria perfil nem entra em grupo. Consultas de negócio usam transações
somente leitura. Veja formatos em [reader](docs/reader.md).

### Proxy e Supabase Auth

Na porta **5173**, `/` e `/assets/...` servem a interface; `/config` expõe apenas
URL/chave pública; `/status` consulta health das APIs. `/writer/api/...` encaminha
rotas permitidas ao Java; `/reader/api/...`, ao Python, mantendo Bearer. Esses
prefixos não existem nas APIs diretas. Login/cadastro/renovação/logout usam
`SUPABASE_URL/auth/v1/...`, não endpoints Java/Python de login.

## Testes e limites

```powershell
npm.cmd --prefix frontend test
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
.\.venv\Scripts\python.exe scripts/writer.py build
```

No Linux use `npm` e `.venv/bin/python`. Os testes Node não precisam de `npm ci`;
para Playwright instale `npm.cmd --prefix frontend ci`. Os cenários em
`frontend/e2e` usam respostas simuladas e não provam integração real das APIs.

`scripts/writer.py integration` cria/remove schema temporário e usa as quatro
credenciais PostgreSQL (admin, migrador, writer, reader). Execute **somente em
banco de teste confirmado como isolado**, com roles/CA preparados; não use banco
compartilhado. Os testes compilam no build comum, mas são ignorados sem
`GAMBLR_IT=true`. `scripts/test_database_schema.py` também exige ambiente isolado
e schema sem tabelas, apesar de executar rollback. Não use como migração.

Resultados desta revisão e bloqueios: [verificação](docs/verificacao.md).
Regras e modelo: [requisitos](docs/requisitos.md) e [modelo](docs/modelo-de-dados.md).
