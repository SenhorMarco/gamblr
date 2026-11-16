# Gamblr

Plataforma web para conversar em grupos e fazer previsões com pontos virtuais.
O frontend consome uma API REST em Java para operações de escrita e uma API
REST em Python para ranking, odds e participação nas apostas.

Projeto Final — Laboratório de Programação II.
Integrantes: **a informar pela equipe antes da entrega**.
Vídeo e divisão do trabalho: preparados pela equipe.

## Organização e tecnologias

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

| Parte | Tecnologia | Endereço padrão |
| --- | --- | --- |
| API Java | Java 21+, Spring Boot 4.1.2, JDBC, Gradle Wrapper | http://127.0.0.1:8080 |
| API Python | Python, Flask, PyJWT, pg8000 | http://127.0.0.1:5000 |
| Frontend | HTML/CSS/JavaScript, servidor e proxy Flask | http://127.0.0.1:5173 |
| Persistência e login | PostgreSQL e Supabase Auth | Projeto Supabase configurado pela equipe |

O site e as duas APIs rodam localmente. O PostgreSQL e a autenticação ficam no
Supabase, portanto é necessária conexão com a internet durante o uso.

## Preparação a partir de um clone novo

### 1. Pré-requisitos e dependências

Instale Git, Python 3.10 ou superior e um JDK 21 ou superior. Confirme que
`python` (ou `python3` no Linux) e `java` estão no PATH. O Gradle Wrapper está
versionado, dispensando uma instalação global do Gradle; seu primeiro uso baixa
a distribuição e as dependências Java. Node.js é opcional e usado apenas para
os testes do frontend, não para executar o site.

No Windows/PowerShell:

```powershell
git clone https://github.com/SenhorMarco/gamblr.git
cd gamblr
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r scripts/requirements.txt -r services/reader/requirements.txt
if (-not (Test-Path -LiteralPath .env)) { Copy-Item -LiteralPath .env.example -Destination .env }
New-Item -ItemType Directory -Force -Path certs
```

No Linux:

```bash
git clone https://github.com/SenhorMarco/gamblr.git
cd gamblr
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/requirements.txt -r services/reader/requirements.txt
test -f .env || cp .env.example .env
mkdir -p certs
```

Os comandos seguintes são executados na raiz do repositório. Não sobrescreva
um `.env` que já esteja configurado.

### 2. Banco e autenticação no Supabase

Em um **projeto Supabase novo**, faça esta preparação uma única vez:

1. Crie o projeto e guarde a senha do PostgreSQL.
2. No SQL Editor, como `postgres`, execute o conteúdo completo de
   `database/bootstrap/001_roles.sql`. Ele cria as roles técnicas e o schema
   privado `gamblr`.
3. Ainda como `postgres`, execute cada arquivo de `database/migrations`,
   integralmente e em ordem de **V001 a V011**. Execute um arquivo por vez e
   prossiga somente após seu sucesso. Em um ambiente existente, aplique apenas
   as migrações ainda não executadas; não recrie roles, schema ou tabelas.
4. Gere senhas distintas para as três roles. Execute a consulta abaixo com
   valores reais escolhidos por você, em uma consulta privada do SQL Editor:

   ```sql
   ALTER ROLE gamblr_migrator LOGIN PASSWORD 'SENHA_DO_MIGRADOR';
   ALTER ROLE gamblr_writer LOGIN PASSWORD 'SENHA_DO_WRITER';
   ALTER ROLE gamblr_reader LOGIN PASSWORD 'SENHA_DO_READER';
   ```

   Os textos acima são exemplos a substituir. Se uma senha contiver aspas
   simples, duplique-as no literal SQL. Não salve a consulta preenchida no Git.
5. Em **Connect → Session pooler**, copie host, porta, banco e usuário. Para o
   pooler, o usuário administrativo é `postgres.PROJECT_REF` e os usuários
   técnicos seguem `gamblr_writer.PROJECT_REF`, `gamblr_reader.PROJECT_REF` e
   `gamblr_migrator.PROJECT_REF`. Use a porta indicada para o **Session pooler**,
   normalmente 5432.
6. Baixe o certificado CA do banco na configuração de SSL do projeto e salve-o
   localmente em `certs/supabase-ca.crt`.
7. Obtenha a **Project URL** e a chave pública **publishable** em API Keys.
   Em Authentication, habilite login por email/senha e use chaves de assinatura
   assimétricas ES256 ou RS256. O schema `gamblr` deve permanecer fora dos schemas
   expostos pela Data API; o frontend acessa os backends locais.
8. Para uma demonstração sem depender da entrega de email, crie duas contas
   de teste em **Authentication → Users → Add user**, com emails confirmados.
   O cadastro normal do site também existe; quando a confirmação de email
   estiver habilitada, confirme a conta antes de entrar.

Preencha o `.env` a partir do modelo. Estes são os grupos de campos necessários:

| Configuração | Variáveis |
| --- | --- |
| Conexão PostgreSQL | `SUPABASE_DB_HOST`, `SUPABASE_DB_PORT`, `SUPABASE_DB_NAME` |
| TLS | `SUPABASE_DB_SSLMODE=verify-full`, `SUPABASE_DB_SSLROOTCERT=certs/supabase-ca.crt` |
| API Java | `GAMBLR_WRITE_DB_USER`, `GAMBLR_WRITE_DB_PASSWORD` |
| API Python | `GAMBLR_READ_DB_USER`, `GAMBLR_READ_DB_PASSWORD` |
| Login do site | `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` |
| Migrações e testes | `GAMBLR_MIGRATION_DB_USER`, `GAMBLR_MIGRATION_DB_PASSWORD` |
| Administração e integração | `SUPABASE_DB_USER`, `SUPABASE_DB_PASSWORD` |

Mantenha `GAMBLR_AUTH_ISSUER` vazio: ele é derivado de `SUPABASE_URL`.
As portas padrão são `GAMBLR_WRITER_PORT=8080` e `GAMBLR_READER_PORT=5000`;
elas devem ser diferentes. `GAMBLR_BIND_ADDRESS=127.0.0.1` limita o acesso à
máquina local. As credenciais de administração e migração não são usadas pelo
site nem pelas APIs durante a execução normal. O navegador recebe apenas a URL
de autenticação e a chave pública; senhas do banco ficam nos backends.

### 3. Executar as três partes

Opção recomendada, com todos os processos no mesmo terminal:

```powershell
.\iniciar.cmd
```

No Linux:

```bash
./iniciar.sh
```

Aguarde **Todos os servicos estao prontos** e abra **http://127.0.0.1:5173**.
Mantenha o terminal aberto. Ctrl+C encerra os processos iniciados pelo comando.
Se a primeira compilação demorar, use `iniciar.cmd --timeout 600` ou
`./iniciar.sh --timeout 600`.

Para iniciar cada parte separadamente, use **três terminais**, sempre na raiz:

| Parte | Windows/PowerShell | Linux | Porta |
| --- | --- | --- | --- |
| API Java | `.\.venv\Scripts\python.exe scripts/writer.py bootRun` | `.venv/bin/python scripts/writer.py bootRun` | 8080 |
| API Python | `.\.venv\Scripts\python.exe scripts/reader.py run` | `.venv/bin/python scripts/reader.py run` | 5000 |
| Frontend | `.\.venv\Scripts\python.exe scripts/frontend.py` | `.venv/bin/python scripts/frontend.py` | 5173 |

Escolha o comando único **ou** os três terminais para não disputar as mesmas
portas. Pare instâncias anteriores com Ctrl+C antes de reiniciar. O frontend
pode usar outra porta com `scripts/frontend.py --port 5174`; no comando único,
use `iniciar.cmd --port 5174` ou `./iniciar.sh --port 5174`.

### 4. Conferir a execução e usar a plataforma

No PowerShell, após iniciar:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/health
Invoke-RestMethod http://127.0.0.1:5000/health
Invoke-RestMethod http://127.0.0.1:5173/status
```

Os dois primeiros devem retornar `status: UP`; o terceiro deve indicar
`writer: true` e `reader: true`. No Linux, use `curl` nos mesmos endereços.

1. Entre no site com uma conta de teste e crie um grupo público com 100 pontos
   iniciais. Abra **Outra sessão**, entre com a segunda conta e participe do grupo.
2. Publique uma aposta com término futuro e duas opções. Com a outra conta,
   confirme um palpite. A carteira, o ranking, as odds e as barras devem refletir
   a participação. O autor da aposta não pode apostar nela.
3. Use o chat do grupo e **Gerenciar grupo**. O dono pode ajustar os três preços
   de personalização; integrantes podem adquirir cores e títulos com pontos.
4. Para testar resultado e pagamento, crie uma aposta curta e aguarde o término.
   O autor publica o resultado; o encerramento segue os prazos configurados.

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

## Endpoints da API Python — porta 5000

| Método | Rota | Uso |
| --- | --- | --- |
| GET | `/health` | Disponibilidade do serviço |
| GET | `/api/groups/{group_id}/ranking` | Ranking dos integrantes por saldo disponível |
| GET | `/api/groups/{group_id}/participation` | Contagens de palpites por opção, em lote por grupo |
| GET | `/api/bets/{bet_id}/odds` | Odds e contagens agregadas da aposta |

## Endpoints da API Java — porta 8080

As rotas abaixo são relativas ao backend Java; `{group}`, `{bet}`, `{user}`,
`{id}` e demais parâmetros representam os identificadores do recurso.

| Método | Rota | Responsabilidade |
| --- | --- | --- |
| GET | `/health` | Disponibilidade do serviço |
| POST | `/api/me` | Perfil, grupos, carteira e apostas |
| POST | `/api/groups` | Perfil, grupos, carteira e apostas |
| GET | `/api/groups` | Perfil, grupos, carteira e apostas |
| GET | `/api/groups/public` | Perfil, grupos, carteira e apostas |
| POST | `/api/groups/{group}/members/me` | Perfil, grupos, carteira e apostas |
| GET | `/api/groups/{group}/wallet/me` | Perfil, grupos, carteira e apostas |
| POST | `/api/groups/{group}/bets` | Perfil, grupos, carteira e apostas |
| GET | `/api/groups/{group}/bets` | Perfil, grupos, carteira e apostas |
| GET | `/api/bets/{bet}` | Perfil, grupos, carteira e apostas |
| POST | `/api/bets/{bet}/predictions` | Perfil, grupos, carteira e apostas |
| GET | `/api/bets/{bet}/prediction/me` | Perfil, grupos, carteira e apostas |
| GET | `/api/users` | Amizades e mensagens |
| GET | `/api/friendships` | Amizades e mensagens |
| POST | `/api/friendships` | Amizades e mensagens |
| POST | `/api/friendships/{id}/accept` | Amizades e mensagens |
| POST | `/api/friendships/{id}/decline` | Amizades e mensagens |
| GET | `/api/groups/{group}/messages` | Amizades e mensagens |
| POST | `/api/groups/{group}/messages` | Amizades e mensagens |
| POST | `/api/groups/{group}/messages/{id}/delete` | Amizades e mensagens |
| GET | `/api/friends/{friend}/messages` | Amizades e mensagens |
| POST | `/api/friends/{friend}/messages` | Amizades e mensagens |
| GET | `/api/groups/{group}/settings` | Administração dos grupos |
| POST | `/api/groups/{group}/settings` | Administração dos grupos |
| GET | `/api/groups/{group}/members` | Administração dos grupos |
| POST | `/api/groups/{group}/members/{user}/role` | Administração dos grupos |
| POST | `/api/groups/{group}/members/{user}/remove` | Administração dos grupos |
| POST | `/api/groups/{group}/leave` | Administração dos grupos |
| POST | `/api/groups/{group}/transfer` | Administração dos grupos |
| POST | `/api/groups/{group}/invitations` | Administração dos grupos |
| GET | `/api/group-invitations` | Administração dos grupos |
| POST | `/api/group-invitations/{id}/accept` | Administração dos grupos |
| POST | `/api/group-invitations/{id}/decline` | Administração dos grupos |
| POST | `/api/groups/{group}/join-requests` | Administração dos grupos |
| GET | `/api/groups/{group}/join-requests` | Administração dos grupos |
| POST | `/api/groups/{group}/join-requests/{id}/approve` | Administração dos grupos |
| POST | `/api/groups/{group}/join-requests/{id}/decline` | Administração dos grupos |
| GET | `/api/bets/{bet}/resolution` | Resultado, contestação e pagamento |
| POST | `/api/bets/{bet}/result` | Resultado, contestação e pagamento |
| POST | `/api/bets/{bet}/contests` | Resultado, contestação e pagamento |
| POST | `/api/bets/{bet}/decision` | Resultado, contestação e pagamento |
| POST | `/api/bets/{bet}/cancel` | Resultado, contestação e pagamento |
| POST | `/api/bets/{bet}/delete` | Resultado, contestação e pagamento |
| GET | `/api/groups/{group}/personalization` | Cores e títulos por pontos |
| POST | `/api/groups/{group}/personalization/prices` | Cores e títulos por pontos |
| POST | `/api/groups/{group}/personalization/purchases` | Cores e títulos por pontos |
| POST | `/api/groups/{group}/members/{user}/title/remove` | Cores e títulos por pontos |


Todas as rotas de negócio exigem `Authorization: Bearer ACCESS_TOKEN` de um
usuário autenticado; `/health` é público. GETs retornam JSON e POSTs recebem
JSON quando há corpo. Operações sem conteúdo podem responder sem corpo, como
envio de mensagem e aceite de amizade (204). Dados inválidos, recursos ausentes
e conflitos são tratados sem encerrar os serviços. Erros de negócio têm
`code` e `message`, com HTTP 400, 403, 404, 409 ou 503 conforme o caso.

O frontend também serve `/config` (apenas configuração pública) e `/status`
(disponibilidade dos dois backends), além dos arquivos em `/assets/`.

`GET /api/users` exige o parâmetro de consulta `name` (pelo menos dois
caracteres). As consultas de mensagens aceitam `before` para paginação.

## Testes

Na raiz, no Windows:

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
.\.venv\Scripts\python.exe scripts/writer.py test
```

No Linux, substitua `.\.venv\Scripts\python.exe` por `.venv/bin/python`.
Os testes Java comuns não exigem o banco. Para integração real, configure
também as credenciais administrativas, de migração e leitura e execute
`scripts/writer.py integration` com o Python do `.venv`. Esse teste cria e remove
um schema temporário; ele não aplica migrações ao schema usado pela aplicação.

Para os testes opcionais da interface, instale Node.js e, na pasta `frontend`:

```powershell
npm.cmd ci
npm.cmd test
.\node_modules\.bin\playwright.cmd test
```

No Linux, use `npm ci`, `npm test` e `npx playwright test`. Os testes de navegador
exigem o site iniciado e Microsoft Edge instalado, conforme a configuração de
`frontend/playwright.config.js`. Eles simulam as respostas dos backends.

## Arquivos de entrega

Repositório: https://github.com/SenhorMarco/gamblr

Mantenha o repositório público ou compartilhado com o professor até a correção.
A equipe completa os integrantes, o link público do vídeo e a divisão do
trabalho antes da entrega. A revisão de organização está em
[conferência do enunciado](docs/conformidade-enunciado.md).

O `.gitignore` exclui `.env`, certificados locais, ambientes virtuais, caches,
`node_modules`, builds e resultados de teste. Apenas `.env.example`, sem
credenciais reais, deve ser versionado. Os arquivos do Gradle Wrapper são
necessários para compilar a API Java e fazem parte da entrega.

## Documentação complementar

- [Requisitos do produto](docs/requisitos.md)
- [Execução local e rede](docs/execucao-local.md)
- [Banco e migrações](docs/banco-de-dados.md)
- [Modelo de dados](docs/modelo-de-dados.md)
- [Supabase](docs/supabase.md) e [autenticação](docs/supabase-auth.md)
- [API Java](docs/writer.md) e [API Python](docs/reader.md)
- [Catálogo completo dos endpoints: finalidade, entrada, resposta e permissões](docs/endpoints.md)
- [Interface](docs/frontend.md) e [layout lateral](docs/layout-lateral.md)
- [Demonstração com duas contas](docs/demonstracao.md)
- [Chats](docs/chats.md), [administração](docs/administracao.md),
  [resultados](docs/resultados.md) e [personalização](docs/personalizacao.md)
