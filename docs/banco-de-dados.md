# Banco de dados: permissões e migrações

A instalação completa está no [README](../README.md#configuração-de-ambiente-banco-e-autenticação).
O código atual exige **V001–V011**. Este guia detalha permissões, conteúdo das
migrações e cuidados de manutenção.

## Responsabilidades

| Role PostgreSQL | Uso | Acesso |
| --- | --- | --- |
| `gamblr_migrator` | Migrações | Dono do schema `gamblr` e de seus objetos |
| `gamblr_writer` | Java/Spring Boot | Consulta e modifica dados; sem DDL |
| `gamblr_reader` | Python/Flask | Consulta somente as três views autorizadas |

São contas técnicas, independentes das contas do Supabase Auth. O schema
`gamblr` permanece fora dos schemas expostos pela Data API. `anon` e
`authenticated` não recebem acesso às tabelas. A autorização de cada usuário
é feita pelas APIs; a conta de leitura não substitui a verificação de participação.

## Ordem de preparação

No SQL Editor do projeto Supabase, como `postgres`:

1. Execute [bootstrap/001_roles.sql](../database/bootstrap/001_roles.sql) uma vez.
   Ele cria as três roles com `NOLOGIN`, concede a `postgres` permissão para
   assumir o migrador e cria o schema privado de propriedade dele.
2. Execute os arquivos abaixo **em ordem numérica, de V001 até V011**, um arquivo
   completo por consulta, incluindo `BEGIN`, `SET LOCAL ROLE` e `COMMIT`.
   Pare em caso de erro. Em banco existente, identifique a última versão aplicada
   e execute somente as seguintes; não repita arquivos nem apague objetos para
   forçar a instalação. Não há executor ou histórico automático de migrações:
   registre as versões aplicadas no ambiente.
3. Habilite login e configure senhas distintas nas roles, conforme o SQL abaixo.
4. Preencha as credenciais no `.env` e execute os verificadores de leitura.

| Migração | Conteúdo |
| --- | --- |
| [V001](../database/migrations/V001__schema_permissions.sql) | Permissões do schema e privilégios padrão |
| [V002](../database/migrations/V002__users_groups_balances.sql) | Usuários, grupos, integrantes e carteiras |
| [V003](../database/migrations/V003__bets_predictions_movements.sql) | Apostas, opções, palpites, movimentos e contestações |
| [V004](../database/migrations/V004__ranking_odds_views.sql) | Views de ranking e odds |
| [V005](../database/migrations/V005__external_identities.sql) | Vínculo entre identidade JWT e perfil interno |
| [V006](../database/migrations/V006__reader_membership_identity.sql) | Identidade do membro para autorização no reader |
| [V007](../database/migrations/V007__friendships_messages.sql) | Amizades e mensagens de grupo e privadas |
| [V008](../database/migrations/V008__bet_resolution.sql) | Apuração, conclusão financeira e histórico de saídas |
| [V009](../database/migrations/V009__group_administration.sql) | Administração, convites, crédito diário e exclusão lógica |
| [V010](../database/migrations/V010__point_based_odds.sql) | Odds calculadas por pontos |
| [V011](../database/migrations/V011__group_personalization.sql) | Preços, cores, títulos e comprovantes de compra |

As migrações de funcionalidades anteriores, como V007 ou V008, não bastam para
executar a versão atual. A V011 também fornece colunas exigidas pelo ranking.
Consulte o [modelo de dados](modelo-de-dados.md) para as garantias transacionais.

## Senhas e conexão

Em uma consulta privada do SQL Editor, como `postgres`, substitua os exemplos
por senhas geradas diferentes:

```sql
ALTER ROLE gamblr_migrator LOGIN PASSWORD 'SENHA_DO_MIGRADOR';
ALTER ROLE gamblr_writer LOGIN PASSWORD 'SENHA_DO_WRITER';
ALTER ROLE gamblr_reader LOGIN PASSWORD 'SENHA_DO_READER';
```

Não execute as senhas de exemplo nem salve a consulta preenchida no Git ou em
consultas compartilhadas. Uma aspa simples na senha deve ser duplicada dentro
do literal SQL; o `.env` recebe a senha original.

No `.env`, use `GAMBLR_MIGRATION_DB_USER`, `GAMBLR_WRITE_DB_USER` e
`GAMBLR_READ_DB_USER`, com suas respectivas variáveis terminadas em `_PASSWORD`.
No Session pooler, os usuários têm o formato `gamblr_writer.PROJECT_REF`, por
exemplo. Todas as contas usam o mesmo host, porta, banco e CA. Os serviços usam
somente suas credenciais; a conta administrativa é reservada à preparação e
às verificações. Veja [conexão e TLS](supabase.md).

## Verificações de leitura

Windows/PowerShell, na raiz, após a instalação das dependências:

```powershell
.\.venv\Scripts\python.exe scripts/check_supabase.py --all --model
.\.venv\Scripts\python.exe scripts/reader.py check
```

No Linux/Bash, substitua o interpretador por `.venv/bin/python`.
O primeiro comando confere contas, metadados, proprietários e permissões; o
segundo confere a conexão do reader e as colunas necessárias às suas consultas.
Nenhum aplica migrações. Para conferir apenas conexão e privilégios do schema,
use `check_supabase.py --all`; para uma role, use `--role migration`, `--role write` ou `--role read`. Sem opções, ele usa a conta administrativa.

No SQL Editor, [check_permissions.sql](../database/check_permissions.sql) e
[check_model.sql](../database/check_model.sql) permitem inspecionar permissões
e estrutura. Esses comandos não verificam se a Data API foi desativada no painel
nem substituem testes das operações das APIs.

## Testes com escrita

Somente em banco de teste isolado, com bootstrap/V001 aplicados e schema sem
tabelas, execute no Windows/PowerShell, na raiz:

```powershell
.\.venv\Scripts\python.exe scripts/test_database_schema.py
```

O script usa o migrador, cria a estrutura e dados dentro de uma transação e
encerra com rollback. Recusa executar se já houver tabelas. Não é um instalador
nem deve ser executado no banco compartilhado da aplicação.

Os [testes de integração Java](writer.md#testes-de-integração-no-postgresql-real)
usam schema exclusivo, HTTP e chamadas concorrentes; exigem banco isolado e
as quatro credenciais. Compilar esses testes não equivale a executá-los.

## Manutenção do modelo

Preserve saldo por usuário/grupo, histórico após saída, palpite único, reserva
transacional, prazos copiados na publicação, ranking por saldo disponível e odds
por pontos. Carteiras e movimentos precisam ser atualizados na mesma transação.
As restrições do banco complementam a autorização e as regras temporais das APIs.

Não altere migrações já aplicadas. Mudanças futuras devem ter novos arquivos
numerados e ser aplicadas em ordem, com registro das versões executadas.

## Referências

- [Roles PostgreSQL no Supabase](https://supabase.com/docs/guides/database/postgres/roles)
- [Privilégios padrão no PostgreSQL](https://www.postgresql.org/docs/current/sql-alterdefaultprivileges.html)
