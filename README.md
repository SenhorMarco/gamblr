# Gamblr

Plataforma de apostas em grupos com **pontos virtuais, sem compra ou saque**.
Permite criar grupos, publicar apostas, dar palpites, acompanhar rankings e odds,
conversar e apurar resultados.

**Integrantes:** Ferreira, Brasil e Kossooski.

## Tecnologias e organização

| Parte | Tecnologias | Pasta | Porta local |
| --- | --- | --- | --- |
| API Java | Java 21, Spring Boot, JDBC e Gradle Wrapper | `services/writer` | 8080 |
| API Python | Python 3.13, Flask, PyJWT e pg8000 | `services/reader` | 5000 |
| Frontend | HTML, CSS e JavaScript; servidor/proxy Flask | `frontend` e `services/web` | 5173 |
| Banco e autenticação | PostgreSQL e Supabase Auth | Serviço remoto; SQL em `database` | — |

As APIs são processos independentes. A API Java cuida das escritas e regras de
negócio; a API Python fornece ranking, participação e odds. O frontend acessa
ambas pelo proxy local. Os scripts de execução estão em `scripts/` e os guias
detalhados em `docs/`.

## Como executar localmente

### 1. Conferir os pré-requisitos

- Git, **JDK 21** (com `javac`), **Python 3.13** com pip/venv e navegador.
- Acesso ao repositório privado, arquivo **`setup.rar`** e programa para extraí-lo.
- Internet: o banco e a autenticação ficam no Supabase da equipe.

Confira `java -version`, `javac -version` e `python --version` (Linux:
`python3 --version`). Se definido, `JAVA_HOME` deve apontar para o JDK 21.
No Windows, prefira o Python de [python.org](https://www.python.org/downloads/windows/).
O Gradle Wrapper baixa o Gradle automaticamente; Node.js só é necessário para
testes do frontend.

### 2. Obter o projeto e extrair a configuração

```bash
git clone https://github.com/SenhorMarco/gamblr.git
cd gamblr
```

Ao extrair `setup.rar`, você obterá uma pasta chamada **`setup`**, contendo o
arquivo **`.env`** e a pasta **`certs`**. Mova ambos para a raiz do projeto
`gamblr/`, no mesmo nível do `README.md`. Depois, exclua a pasta `setup`, que
estará vazia. O resultado deve ser:

```text
gamblr/
├── .env
├── certs/
│   └── supabase-ca.crt
├── frontend/
├── services/
├── scripts/
├── iniciar.cmd
├── iniciar.sh
└── README.md
```

Preserve os nomes fornecidos, inclusive `.env` (sem `.txt`), e o caminho do certificado
indicado em `SUPABASE_DB_SSLROOTCERT`.

**Não é necessário criar um projeto no Supabase nem executar migrações.**
O pacote fornece a configuração do banco e do Auth previamente preparados pela
equipe. O repositório é privado e o `setup.rar` deve ser compartilhado somente
com usuários autorizados. O Git ignora `.env`, `certs/` e `setup.rar`.

### 3. Instalar as dependências

Execute os comandos **na raiz do projeto**.

**Windows / PowerShell:**

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r scripts/requirements.txt -r services/reader/requirements.txt
```

**Linux / Bash:**

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/requirements.txt -r services/reader/requirements.txt
```

Não é preciso ativar a `.venv`; os comandos usam seu Python diretamente.
Instale os dois arquivos de requisitos. No Linux, é necessário ter o suporte a
`venv` instalado; uma `.venv` criada no Windows não pode ser reutilizada.

> **Atenção:** caso você esteja conectado à rede do IME, é necessário ligar uma
> VPN antes de iniciar o projeto, utilizando, por exemplo, o Cloudflare One Client.

### 4. Iniciar e acessar

**Windows / PowerShell:**

```powershell
.\iniciar.cmd
```

**Linux / Bash:**

```bash
./iniciar.sh
```

Aguarde **Todos os servicos estao prontos** e acesse **http://127.0.0.1:5173**.
O primeiro início pode demorar para baixar dependências Java e compilar.
Se necessário, acrescente `--timeout 600` ao comando. Use **Ctrl+C** para
encerrar os processos; nas próximas execuções, basta repetir este passo.

Para iniciar **cada parte separadamente**, abra três terminais na raiz e use:

| Parte | Comando no Windows | Endereço |
| --- | --- | --- |
| API Java | `.\.venv\Scripts\python.exe scripts/writer.py bootRun` | http://127.0.0.1:8080 |
| API Python | `.\.venv\Scripts\python.exe scripts/reader.py run` | http://127.0.0.1:5000 |
| Frontend/proxy | `.\.venv\Scripts\python.exe scripts/frontend.py` | http://127.0.0.1:5173 |

No Linux, substitua `.\.venv\Scripts\python.exe` por `.venv/bin/python`.
Escolha o comando único ou os três terminais, evitando iniciar duas instâncias.
Os lançadores carregam automaticamente o `.env`.

## Experimentar a plataforma

1. Cadastre-se e confirme o email, quando solicitado, ou entre com uma conta existente.
2. Crie um grupo ou entre em um grupo público. Grupos privados usam convites.
3. Publique uma aposta com opções, prazo e valor mínimo. Use **Outra sessão**
   com uma segunda conta para participar: o autor não pode palpitar na própria aposta.
4. Acompanhe saldo, ranking e odds. Os pontos do palpite ficam reservados;
   cada participante pode dar apenas um palpite, que é definitivo.
5. Após o término, publique o resultado. Participantes podem contestar e o dono
   do grupo decide as contestações. Explore também chats e personalização.

Para demonstrar a integração, abra a aba **Network** do navegador: criar grupos
e palpitar chama `/writer/api/...` (Java); consultar ranking e odds chama
`/reader/api/...` (Python). O proxy encaminha essas chamadas para as portas 8080
e 5000. Login e renovação de sessão usam o Supabase Auth.

## Endpoints das APIs

As rotas abaixo são acessadas diretamente nas portas de cada API. Exceto
`/health`, exigem `Authorization: Bearer ACCESS_TOKEN` do Supabase Auth e as
permissões indicadas. Parâmetros entre chaves são UUIDs, salvo o ID de mensagem.
Consultas retornam JSON; algumas ações retornam corpo vazio. Erros das APIs
usam `code` e `message`.

### Java — porta 8080

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

### Python — porta 5000

| Método | Caminho | Finalidade / autorização adicional |
| --- | --- | --- |
| GET | `/health` | Estado HTTP, sem banco; público |
| GET | `/api/groups/{group}/ranking` | Ranking por saldo disponível; membro |
| GET | `/api/groups/{group}/participation` | Votos agregados das apostas do grupo; membro |
| GET | `/api/bets/{bet}/odds` | Odds por pontos e votos; membro; `X-Odds-Version` opcional para long polling |

Veja corpos de requisição, respostas e permissões no
[catálogo de endpoints](docs/endpoints.md).

## Problemas comuns

| Problema | Solução |
| --- | --- |
| `Flask` ou `jwt` ausente | Instale os dois arquivos de requisitos usando o Python da `.venv`. |
| Certificado ausente ou falha TLS | Confira se `.env` e `certs/` estão na raiz e se o caminho do certificado corresponde ao arquivo extraído. |
| Falha de conexão com o banco | Confira a internet e solicite à equipe a revisão do acesso ou um `setup.rar` atualizado. |
| Login rejeitado | Confira email, senha e confirmação do email. Persistindo, contate a equipe. |
| Porta ocupada | Encerre a instância anterior. Para trocar apenas a porta do site, use `--port 5174` no comando único. |
| Demora na inicialização | Confira os logs e tente `--timeout 600`, especialmente no primeiro início. |

Para diagnosticar o banco, execute
`.\.venv\Scripts\python.exe scripts/reader.py check` (Linux: `.venv/bin/python`).
Os endereços `http://127.0.0.1:8080/health`, `http://127.0.0.1:5000/health` e
`http://127.0.0.1:5173/status` verificam a disponibilidade HTTP, não o login.

## Testes e documentação

Testes básicos, na raiz, com PowerShell:

```powershell
npm.cmd --prefix frontend test
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
.\.venv\Scripts\python.exe scripts/writer.py build
```

No Linux, use `npm` e `.venv/bin/python`. O build comum não executa integração
com banco sem `GAMBLR_IT=true`. Testes de integração e de schema devem usar um
banco de teste isolado, não o banco compartilhado do `setup.rar`.

- [Execução local e opções avançadas](docs/execucao-local.md)
- [Regras da plataforma](docs/requisitos.md) e [modelo de dados](docs/modelo-de-dados.md)
- [API Java](docs/writer.md), [API Python](docs/reader.md) e [frontend](docs/frontend.md)
- [Banco e migrações](docs/banco-de-dados.md) e [Supabase Auth](docs/supabase-auth.md):
  preparação da infraestrutura pela equipe, dispensada para quem usa o `setup.rar`.
