# Banco de dados: permissões e migrações

Para cores e títulos por pontos, após V001–V010, execute o arquivo completo
[V011__group_personalization.sql](../database/migrations/V011__group_personalization.sql)
como `postgres`. Ele acrescenta preços, personalizações, comprovantes de compra
e débito de pontos, com RLS e permissões. Veja [personalização](personalizacao.md).

Para administração e crédito diário, após V001–V008, execute o arquivo completo
[V009__group_administration.sql](../database/migrations/V009__group_administration.sql)
como `postgres`. Veja [administracao.md](administracao.md).

Para o ciclo de resultados, após V001–V007, execute somente a nova
[V008__bet_resolution.sql](../database/migrations/V008__bet_resolution.sql)
como `postgres` no SQL Editor. Veja [resultados.md](resultados.md).

Para um ambiente que já tem V001–V006, a etapa de chats acrescenta somente
[V007__friendships_messages.sql](../database/migrations/V007__friendships_messages.sql).
Execute o arquivo completo como `postgres` no SQL Editor; ele já habilita RLS.
Veja a ativação e o roteiro em [chats.md](chats.md).

Os scripts desta etapa são aplicados manualmente no SQL Editor. O repositório
contém roles, permissões, tabelas de domínio e views; confira a aplicação em cada
ambiente com os verificadores descritos abaixo.

## Responsabilidades

| Role PostgreSQL | Uso | Acesso previsto |
| --- | --- | --- |
| `gamblr_migrator` | Migrações | Dono do schema `gamblr` e de seus objetos |
| `gamblr_writer` | Spring Boot | Consultar e modificar dados; sem criar ou alterar tabelas |
| `gamblr_reader` | Flask | Consultar apenas views concedidas explicitamente |

São contas técnicas do banco, independentes das contas dos usuários do Gamblr.
O cadastro/login da aplicação usa Supabase Auth, conforme a decisão registrada
em [supabase-auth.md](supabase-auth.md).
O schema `gamblr` deve permanecer fora dos schemas expostos pela Data API.

## 1. Criar as roles e o schema

No projeto de desenvolvimento do Supabase, abra **SQL Editor → New query**.
Selecione a role administrativa `postgres`, copie o conteúdo de
[`database/bootstrap/001_roles.sql`](../database/bootstrap/001_roles.sql) e execute.
Não é necessário colocar senha nesse arquivo.

O bootstrap cria as três roles com `NOLOGIN`, dá a `postgres` permissão para assumir
o migrador e cria o schema de propriedade dele. Esse bootstrap é separado das
migrações porque as aplicações não terão permissão para administrar roles.

O arquivo é executado uma única vez. Se houver erro, não continue os passos
seguintes. Em particular, se uma role ou schema já existir, investigue antes de
tentar novamente; não apague objetos para forçar a execução.

## 2. Aplicar a primeira migração

Abra uma nova consulta, copie TODO o conteúdo de
[`V001__schema_permissions.sql`](../database/migrations/V001__schema_permissions.sql)
e execute como `postgres`. Ela assume `gamblr_migrator` apenas durante a transação,
define o acesso ao schema e as permissões para objetos futuros.

O leitor ainda não acessa tabelas. Mais adiante, views de ranking e agregados de
odds serão concedidas explicitamente. As APIs precisarão verificar também se
o usuário autenticado pertence ao grupo: somente leitura não substitui autorização.

Execute [`database/check_permissions.sql`](../database/check_permissions.sql).
Compare os resultados com os comentários. Esses testes verificam a configuração
inicial do schema; as permissões sobre tabelas e views serão testadas quando elas
existirem.

## 3. Definir senhas sem colocá-las no Git

Gere três senhas diferentes e guarde-as em um gerenciador de senhas. Em uma consulta
privada do SQL Editor, substitua os exemplos abaixo e execute como `postgres`:

```sql
ALTER ROLE gamblr_migrator LOGIN PASSWORD 'SUBSTITUA_PELA_SENHA_DO_MIGRADOR';
ALTER ROLE gamblr_writer LOGIN PASSWORD 'SUBSTITUA_PELA_SENHA_DO_WRITER';
ALTER ROLE gamblr_reader LOGIN PASSWORD 'SUBSTITUA_PELA_SENHA_DO_READER';
```

Não execute com os textos de exemplo e não salve as senhas em arquivos SQL do
repositório. Em literais SQL, uma aspa simples dentro da senha precisa ser escrita
duas vezes. Se preferir, use senhas aleatórias longas sem aspas simples para
facilitar esse passo. Considere a consulta preenchida como um segredo: não a
compartilhe nem a mantenha como uma consulta compartilhada no painel.

## 4. Preparar as conexões dos serviços

Mantenha os valores administrativos atuais no `.env` por enquanto. Adicione,
sem sobrescrever as configurações de host, banco e certificado:

```dotenv
GAMBLR_MIGRATION_DB_USER=gamblr_migrator.SEU_PROJECT_REF
GAMBLR_MIGRATION_DB_PASSWORD=""
GAMBLR_WRITE_DB_USER=gamblr_writer.SEU_PROJECT_REF
GAMBLR_WRITE_DB_PASSWORD=""
GAMBLR_READ_DB_USER=gamblr_reader.SEU_PROJECT_REF
GAMBLR_READ_DB_PASSWORD=""
```

Substitua o sufixo depois do ponto pelo mesmo project-ref do usuário administrativo
no Session pooler e preencha cada senha. As três conexões usam o mesmo host, porta,
banco e certificado já validados. Fora do pooler, o formato do usuário pode mudar.
Os serviços ainda não carregam essas variáveis; a integração será feita no código.

### Validar as três contas

Com as novas variáveis preenchidas, execute na raiz do projeto:

```powershell
.\.venv\Scripts\python.exe scripts/check_supabase.py --all
```

O verificador conecta separadamente com cada credencial, valida TLS, confere a
identidade da role e consulta suas permissões iniciais no schema. Apenas o migrador
deve poder criar objetos; writer e reader não devem herdar seus privilégios.
Nenhum dado é alterado. O comando retorna código diferente de zero se alguma
conta falhar. Para testar apenas uma conta, use `--role migration`, `--role write`
ou `--role read`. Sem argumentos, continua testando a conta administrativa.

Essa validação não comprova ainda as permissões sobre as futuras tabelas e views.

## 5. Próximas migrações

As primeiras tabelas e views estão preparadas nos seguintes arquivos:

1. [`V002__users_groups_balances.sql`](../database/migrations/V002__users_groups_balances.sql): usuários, grupos, integrantes e carteiras.
2. [`V003__bets_predictions_movements.sql`](../database/migrations/V003__bets_predictions_movements.sql): apostas, opções, palpites, movimentações e contestações.
3. [`V004__ranking_odds_views.sql`](../database/migrations/V004__ranking_odds_views.sql): views e permissões de leitura para Flask.

Leia [modelo-de-dados.md](modelo-de-dados.md) para conhecer as garantias e os limites.
O esquema prepara a persistência. O [serviço Spring Boot](writer.md) implementa
criação de grupo, publicação e reserva de palpites; a distribuição ainda não foi implementada.

### Testar antes de aplicar

Com V001 aplicada e o schema ainda sem tabelas de domínio, execute:

```powershell
.\.venv\Scripts\python.exe scripts/test_database_schema.py
```

O script usa a conta do migrador, abre uma transação, cria a estrutura, executa
dados/verificações temporários e sempre encerra com `ROLLBACK`. Não deixa tabelas
ou dados persistidos. Se já houver tabelas no schema, recusa executar. Testa
restrições, views e privilégios efetivos cadastrados; não simula endpoints nem
o fluxo concorrente de reserva/liquidação ainda não implementado.

### Aplicar definitivamente

No SQL Editor como `postgres`, execute o conteúdo completo de cada arquivo,
na ordem **V002 → V003 → V004**, uma consulta por arquivo. O `SET LOCAL ROLE`
mantém o migrador como proprietário dos objetos. Se ocorrer erro, pare e não
execute o próximo arquivo. Não execute bootstrap nem V001 novamente.

Registre a versão aplicada no ambiente. Cada arquivo tem sua própria transação;
não há controle automático de histórico/checksums nesta etapa. Após aplicar,
confira novamente as contas com `scripts/check_supabase.py --all` e confira
as permissões com `database/check_model.sql` no SQL Editor.

Para conferir automaticamente a estrutura aplicada, o proprietário dos objetos,
as permissões e as três conexões em modo somente leitura, use:

```powershell
.\.venv\Scripts\python.exe scripts/check_supabase.py --all --model
```

Esse comando pode ser repetido após aplicar as migrações. Diferentemente de
`test_database_schema.py`, ele não cria tabelas nem dados temporários: consulta
apenas os metadados. Não verifica pelo PostgreSQL se a Data API foi desativada no
painel nem substitui os futuros testes do backend.

### Evolução posterior

A implementação Java usa **Gradle**. Para integrar identidades JWT, há também
[`V005__external_identities.sql`](../database/migrations/V005__external_identities.sql).
Ela deve ser aplicada uma vez após V004, antes de usar `POST /api/me` no serviço.
O guia [writer.md](writer.md) descreve configuração, endpoints e testes.
Para habilitar a autorização do Flask, aplique também
[`V006__reader_membership_identity.sql`](../database/migrations/V006__reader_membership_identity.sql).
Ela amplia a view de integrantes com o vínculo JWT e mantém 11 tabelas e 3 views.
O guia [reader.md](reader.md) descreve a execução e as consultas.

A evolução dos serviços e das transações deve preservar os seguintes pontos:

- Saldo independente por usuário/grupo e histórico preservado após saída.
- Palpite único por aposta/participante e opção pertencente à aposta correta.
- Reserva e registro do palpite na mesma transação, com controle de concorrência.
- Prazos do grupo copiados para a aposta na publicação e instantes com fuso.
- Ranking por saldo disponível, desempate pela criação da conta.
- Odds por contagem de votos; distribuição por valores apostados.
- Histórico de movimentações e proteção contra liquidação/crédito duplicados.

Não criaremos tabelas de prêmio: essa possibilidade continua em aberto. O provedor
de autenticação escolhido é Supabase Auth. As tabelas sozinhas também não implementam regras
temporais, autorização ou transações: isso fará parte da implementação do backend.

Cada alteração terá um arquivo `V002__...sql`, `V003__...sql` etc. Não altere uma
migração já aplicada. Até escolher e integrar o executor de migrações, registre
quais versões foram executadas no ambiente; esta etapa manual ainda não tem
controle automático de versões/checksums.

## Referências

- [Roles PostgreSQL no Supabase](https://supabase.com/docs/guides/database/postgres/roles)
- [Privilégios padrão no PostgreSQL](https://www.postgresql.org/docs/current/sql-alterdefaultprivileges.html)
