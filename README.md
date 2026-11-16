# Gamblr

Plataforma de apostas em grupos com **pontos virtuais**, sem compra ou saque.
Permite criar grupos, publicar apostas, reservar palpites, acompanhar ranking e
odds, conversar e apurar resultados. Integrantes: **Ferreira, Brasil e Kossooski**.

Funcionalidades: grupos públicos e privados, convites e moderação, crédito
diário, Feed de apostas, amizades e chats, apuração e contestação de resultados
e compra de cores e títulos com os pontos do grupo.

## Arquitetura e tecnologias

| Componente | Localização | Responsabilidade | Porta |
| --- | --- | --- | --- |
| Java 21 / Spring Boot 4.1.2 | `services/writer` | Operações de domínio, transações e autorização | 8080 |
| Python / Flask 3.1.3 | `services/reader` | Ranking, participação e odds | 5000 |
| HTML, CSS e JavaScript / proxy Flask | `frontend` e `services/web` | Interface e acesso às duas APIs | 5173 |
| PostgreSQL / Supabase Auth | Projeto Supabase | Persistência e autenticação | Serviço remoto |

Java/Spring Boot cuida das escritas e regras do domínio; Python/Flask consulta
ranking, participação e odds. O frontend HTML/CSS/JavaScript consome as duas APIs
por um proxy Python de mesma origem. PostgreSQL e autenticação por email/senha
ficam no **Supabase**. Os três processos rodam localmente, mas precisam de internet
para banco e Auth; o projeto não funciona offline.

## Organização do repositório

```text
gamblr/
├── services/
│   ├── writer/       # API Java: Spring Boot, JDBC e Gradle Wrapper
│   ├── reader/       # API Python: Flask, PyJWT e pg8000
│   └── web/          # Servidor do frontend e proxy HTTP local
├── frontend/
│   ├── static/       # HTML, CSS e JavaScript nativos
│   ├── tests/        # Testes JavaScript
│   └── e2e/          # Testes de navegador com Playwright
├── database/
│   ├── bootstrap/    # Criação inicial das roles e do schema
│   ├── migrations/   # V001 a V011, em ordem
│   └── tests/        # Verificações do banco
├── scripts/          # Preparação, execução e testes
├── docs/             # Documentação complementar
├── .env.example      # Modelo de configuração sem credenciais
└── README.md
```

As três partes exigidas pelo enunciado estão em pastas distintas:
`services/reader` (API Python), `services/writer` (API Java) e `frontend`
(interface). `services/web` serve os arquivos da interface e encaminha
requisições; as duas APIs continuam sendo processos independentes.

## Pré-requisitos

- Git, **JDK 21** (incluindo `javac`), **Python 3.13** com `venv`/pip e navegador atual.
- Projeto Supabase e acesso às configurações de banco e Auth e ao SQL Editor.
- Internet para o Supabase e para baixar dependências no primeiro uso.
- No Windows, prefira Python de [Python.org](https://www.python.org/downloads/windows/).
  A edição Microsoft Store pode deixar processos descendentes ativos ao encerrar
  o supervisor; use a distribuição convencional para o comando único.

O Gradle Wrapper **9.9.1** dispensa instalação global do Gradle. Node.js é
necessário apenas para testes do frontend; não há build JavaScript obrigatório.
As dependências Python são Flask 3.1.3, PyJWT com criptografia 2.15.1,
pg8000 1.31.6 e python-dotenv 1.2.4, instaladas pelos dois arquivos de requisitos.

Confira `java -version`, `javac -version` e `python --version` no PowerShell;
no Linux/Bash, use `python3 --version`. `JAVA_HOME`, se definido, deve apontar
para o JDK 21. No Linux, instale o suporte a `venv` do Python.

## Primeira instalação

### 1. Obter o projeto

Windows/PowerShell ou Linux/Bash, na pasta onde deseja guardar o projeto:

```powershell
git clone https://github.com/SenhorMarco/gamblr.git
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

## Configuração de ambiente, banco e autenticação

### 1. Arquivo local e certificado

Crie um projeto Supabase de desenvolvimento dedicado. Em **Connect → Session
pooler**, obtenha host, porta, banco e usuário. Na raiz do projeto, copie o modelo
somente se não existir (Windows/PowerShell):

```powershell
if (-not (Test-Path -LiteralPath .env)) { Copy-Item -LiteralPath .env.example -Destination .env }
```

No Linux/Bash, na raiz:

```bash
test -e .env || cp .env.example .env
```

Preencha o arquivo local; nunca envie senhas/tokens ao Git. Preserve valores existentes e use senhas distintas.

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

### 2. Roles, migrações e Auth

Em projeto novo, no **SQL Editor**, como `postgres`:

1. Execute [bootstrap/001_roles.sql](database/bootstrap/001_roles.sql) completo.
   Cria as roles `gamblr_migrator`, `gamblr_writer`, `gamblr_reader` sem login e o
   schema privado `gamblr`.
2. Execute todos os arquivos de [database/migrations](database/migrations), um
   por consulta, na ordem **V001 → V002 → V003 → V004 → V005 → V006 → V007 → V008 →
   V009 → V010 → V011**. Cada arquivo controla sua transação e assume o migrador.
   Pare em caso de erro. Não repita migrações aplicadas nem altere seus arquivos.
   Registre as versões: não há executor/histórico automático de migrações.
3. Em consulta privada do SQL Editor, habilite login nas três roles com senhas
   diferentes, usando o modelo abaixo. Substitua os exemplos por senhas geradas;
   não salve a consulta preenchida no Git ou em consultas compartilhadas. Preencha
   as três credenciais no `.env` (no pooler, acrescente `.PROJECT_REF` ao usuário).
4. Mantenha `gamblr` fora dos schemas expostos pela Data API. Os serviços usam
   PostgreSQL diretamente; não conceda tabelas a `anon`/`authenticated`. O writer
   não recebe DDL; o reader lê apenas views explicitamente autorizadas.
5. No Auth, habilite Email/senha e assinatura **ES256 ou RS256**. Projeto legado
   somente HS256 precisa preparar a migração para chaves assimétricas. Configure
   Site URL/redirects para o endereço local se testar confirmação de email.
   Para experimentar com duas pessoas, crie duas contas de desenvolvimento
   confirmadas em **Authentication → Users → Add user → Create new user**, com
   senhas próprias e confirmação individual. Não desabilite a confirmação global.
   O cadastro pela interface também pode ser usado; a entrega de emails depende
   das configurações de envio/SMTP do projeto.

Modelo SQL para o passo 3, no SQL Editor como `postgres`:

```sql
ALTER ROLE gamblr_migrator LOGIN PASSWORD 'SENHA_DO_MIGRADOR';
ALTER ROLE gamblr_writer LOGIN PASSWORD 'SENHA_DO_WRITER';
ALTER ROLE gamblr_reader LOGIN PASSWORD 'SENHA_DO_READER';
```

Não execute os valores de exemplo. Uma aspa simples dentro da senha deve ser
escrita duas vezes no literal SQL; guarde a senha original no `.env`.

V005 vincula identidades; V006 autoriza consultas; V007 acrescenta chats; V008,
resultados/pagamentos; V009, administração/crédito diário; V010, odds por pontos;
V011, personalização e colunas exigidas pelo ranking atual. O código atual requer
**V011**, mesmo quando um guia de funcionalidade descreve uma migração anterior.
Detalhes: [banco](docs/banco-de-dados.md), [TLS/conexão](docs/supabase.md) e
[Auth](docs/supabase-auth.md). Não execute a instalação sobre banco compartilhado
existente sem revisar o estado e planejar a atualização.

### 3. Conferir configuração e compilar

Windows/PowerShell, na raiz do projeto:

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

Na raiz do projeto, após instalar e configurar, execute `.\iniciar.cmd` no
Windows/PowerShell ou `./iniciar.sh` no Linux/Bash. Aguarde **Todos os servicos estao prontos** e abra **http://127.0.0.1:5173**.
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

### Conferir disponibilidade

Windows/PowerShell, em qualquer diretório:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health
Invoke-RestMethod http://127.0.0.1:5000/health
Invoke-RestMethod http://127.0.0.1:5173/status
```

No Linux/Bash, use `curl` com os mesmos endereços. Os endpoints `/health`
retornam `UP`; `/status` informa a disponibilidade HTTP das duas APIs.
Essas respostas não verificam o login ou as operações de banco.

## Como usar

1. **Entrar:** cadastre-se e confirme o email, quando exigido pelo Supabase,
   ou use uma conta já confirmada. O login provisiona o perfil no Java.
2. **Criar ou encontrar um grupo:** use **Criar grupo** para definir nome,
   visibilidade, entrada, pontos e prazos. Em **Explorar grupos**, entre em um
   grupo público ou solicite acesso. Grupos privados usam convites.
3. **Publicar e palpitar:** em **Nova aposta**, informe pergunta, opções, término
   e valor mínimo. O autor não pode palpitar na própria proposta. Use **Outra
   sessão** para entrar com uma segunda conta, ingressar no grupo e participar.
   Um palpite de 30 com saldo inicial de 100 deixa 70 disponíveis e 30 reservados,
   considerando somente essa operação. O palpite é único e definitivo; novos
   palpites fecham aos 90% da duração da aposta.
4. **Acompanhar:** consulte as apostas do grupo ou o **Feed**, com filtros por
   grupo, situação, data e texto. O ranking usa saldo disponível. As odds usam
   total de pontos dividido pelos pontos da opção; as barras contam pessoas.
   Opção sem pontos exibe “—”. As odds são atualizadas por long polling.
5. **Conversar:** use o chat do grupo ou envie um pedido em **AMIGOS**. O chat
   privado fica disponível após o aceite e não oferece apostas.
6. **Apurar:** depois do término, o autor publica o resultado. Participantes
   podem contestar no prazo, e o dono decide as contestações. O Java processa
   pagamentos e devoluções; mantenha-o em execução para apuração e crédito diário.
7. **Gerenciar e personalizar:** em **Gerenciar grupo**, consulte integrantes,
   convide, saia ou use as ações permitidas ao seu papel. O dono configura
   preços de cor do nome, texto e cor do título; compras usam saldo disponível.

Para observar a integração, abra a aba **Network** do navegador: criação,
mensagens e palpites usam `/writer/api/...`; ranking, participação e odds usam
`/reader/api/...`. Login e renovação de sessão acessam o Supabase Auth.
O [roteiro por terminal](docs/demonstracao.md) aprofunda publicação e reserva
com duas contas, incluindo a rejeição de palpite duplicado.

## Integração real entre frontend e APIs

O navegador usa uma única origem, na porta 5173. O proxy de `services/web`
encaminha `/writer/api/...` para a API Java na porta 8080 e `/reader/api/...`
para a API Python na porta 5000. A autenticação usa o Supabase Auth diretamente.

| Ação na interface | API utilizada | Exemplos de requisições no Network |
| --- | --- | --- |
| Criar grupo, publicar aposta, confirmar palpite, consultar carteira | Java | `/writer/api/groups`, `/writer/api/groups/{group}/bets`, `/writer/api/bets/{bet}/predictions` |
| Consultar ranking, odds e barras de participação | Python | `/reader/api/groups/{group}/ranking`, `/reader/api/bets/{bet}/odds`, `/reader/api/groups/{group}/participation` |
| Conversar, moderar e comprar personalizações | Java | `/writer/api/groups/{group}/messages`, `/writer/api/groups/{group}/personalization/purchases` |

As chamadas ficam centralizadas em `frontend/static/app.js`, na função `api`;
os módulos de chat, grupos e apresentação recebem essa função. A aba Network
do navegador permite identificar qual backend atende cada ação.

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

## Problemas comuns

| Sintoma | O que conferir |
| --- | --- |
| Porta ocupada | Encerre a instância anterior no terminal; mantenha portas diferentes para os três processos. `--port 5174` troca somente a porta do site. |
| `Flask` ou `jwt` ausente | Use o Python da `.venv` e instale os dois arquivos de requisitos da primeira instalação. |
| Certificado ausente ou falha TLS | Confira o PEM e `SUPABASE_DB_SSLROOTCERT`; mantenha a validação do certificado. |
| Erro de conexão ou permissão no banco | Confira host/usuário do Session pooler, senha da role, internet e migrações até V011. Use `check_supabase.py --all --model` e `reader.py check`. |
| Login rejeitado | Confira email confirmado, Project URL, chave pública e assinatura ES256/RS256 no Auth. |
| HTTP 401 ou 403 | 401 indica token ausente/inválido; 403 pode indicar falta de participação ou de permissão para a ação. |
| Inicialização excede o tempo | Confira os logs; no primeiro download do Gradle, use `--timeout 600`. |

## Testes e limites

Windows/PowerShell, na raiz do projeto:

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

Regras e modelo: [requisitos](docs/requisitos.md) e [modelo](docs/modelo-de-dados.md).

## Arquivos de entrega

Repositório: https://github.com/SenhorMarco/gamblr

Mantenha o repositório público ou compartilhado com o professor até a correção.
A equipe completa o link público do vídeo e a divisão do trabalho antes da entrega. A revisão de organização está em
[conferência do enunciado](docs/conformidade-enunciado.md).

O `.gitignore` exclui `.env`, certificados locais, ambientes virtuais, caches,
`node_modules`, builds e resultados de teste. Apenas `.env.example`, sem
credenciais reais, deve ser versionado. Os arquivos do Gradle Wrapper são
necessários para compilar a API Java e fazem parte da entrega.

## Documentação complementar

- Operação: [execução local](docs/execucao-local.md), [Supabase](docs/supabase.md),
  [banco e migrações](docs/banco-de-dados.md) e [autenticação](docs/supabase-auth.md).
- Componentes: [Java](docs/writer.md), [Python](docs/reader.md) e [frontend](docs/frontend.md).
- Funcionalidades: [chats](docs/chats.md), [administração](docs/administracao.md),
  [resultados](docs/resultados.md) e [personalização](docs/personalizacao.md).

- [Catálogo completo dos endpoints: finalidade, entrada, resposta e permissões](docs/endpoints.md)
