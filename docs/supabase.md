# Configuração inicial do Supabase

Esta etapa prepara o PostgreSQL hospedado no Supabase. Spring Boot e Flask
executarão na máquina anfitriã; os computadores da rede local acessarão os
serviços dessa máquina. A máquina anfitriã precisará de acesso à internet para
conectar ao banco.

## 1. Criar o projeto

1. Acesse [o painel do Supabase](https://supabase.com/dashboard).
2. Crie ou selecione uma organização e escolha **New project**.
3. Use um nome como `gamblr-dev`, para identificar o ambiente de desenvolvimento.
4. Escolha a região mais próxima da máquina que executará os serviços.
5. Gere uma senha forte para o banco e guarde-a em um gerenciador de senhas.
6. Confira o plano escolhido e aguarde o provisionamento.

Se o projeto já existir, use-o e prossiga para a conexão. Não é necessário criar
outro banco dentro dele: o banco padrão `postgres` atende à configuração inicial.

## 2. Obter a conexão

No projeto, abra **Connect** e selecione **Session pooler**.
Copie o host, o usuário e a porta exatamente como o painel informar.

O Session pooler permite conectar a partir de redes IPv4. A porta normalmente é
`5432`. Não confunda com **Transaction pooler**, que usa a porta `6543` e tem
restrições de prepared statements. A conexão direta também pode ser usada quando
a rede tiver conectividade compatível com o endpoint apresentado pelo painel.

O usuário administrativo no Session pooler tem o formato `postgres.<project-ref>`.
A senha é a senha do banco, não uma API key, senha da conta Supabase ou token de
acesso. Para essa conexão PostgreSQL, não precisamos de `anon`, `publishable` ou
`service_role` keys.

## 3. Guardar a configuração local

Na raiz do repositório, execute uma única vez no PowerShell:

```powershell
Copy-Item -LiteralPath .env.example -Destination .env
```

Se `.env` já existir, edite-o sem sobrescrever os valores existentes. Preencha os
campos com os dados do painel e a senha do banco; mantenha `SUPABASE_DB_SSLMODE=require`.
Não envie a senha pelo chat. `.env` está no `.gitignore`; `.env.example` é o modelo
versionável e deve continuar sem credenciais reais.

O arquivo é apenas uma configuração preparada para a integração: Spring Boot,
Flask e PowerShell não o carregarão automaticamente sem configuração adicional.
Guarde a senha como valor separado. Se depois montar uma URI PostgreSQL, caracteres
reservados da senha precisam de percent-encoding; não aplique esse encoding ao
valor separado da senha.

## 4. Acesso aos dados

A divisão técnica inicial é Spring Boot para escritas e Flask para consultas de
ranking e odds. As conexões PostgreSQL ficam nos backends.

Para esse uso, a Data API não é necessária: no painel, abra a visão geral da
integração **Data API** e desative **Enable Data API**. Faça isso no projeto de
desenvolvimento do Gamblr; se estiver reaproveitando um projeto com outras
aplicações, confira primeiro se elas dependem dessa API.

Antes de conectar os serviços, criaremos credenciais distintas: uma para migrações,
uma para escrita e outra para leitura. O usuário administrativo desta configuração
inicial não será a credencial de execução dos serviços. As permissões de leitura
devem respeitar a privacidade dos palpites (RF-14), e os endpoints também precisarão
autorizar o acesso por membro do grupo.

## 5. Validar

No **SQL Editor**, execute esta consulta sem alterar dados:

```sql
select current_database() as banco,
       current_user as usuario,
       now() as horario_atual,
       current_setting('TimeZone') as fuso_da_sessao;
```

Isso confirma o funcionamento do banco no painel, mas ainda não testa a conexão
da máquina anfitriã. Para testar usando o `.env`, execute na raiz do projeto:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r scripts/requirements.txt
.\.venv\Scripts\python.exe scripts/check_supabase.py
```

Se `.venv` já existir e as dependências estiverem instaladas, basta o último
comando. O verificador não exibe credenciais e executa apenas uma consulta em
modo somente leitura. Ele exige TLS e valida também o certificado e o hostname.
O TLS é conferido no socket local entre a máquina e o endpoint. No Session
pooler, `pg_stat_ssl` descreve o trecho entre pooler e PostgreSQL e não deve ser
usado para concluir se a conexão do cliente com o pooler está criptografada.
O arquivo `.env` é lido sem expansão de variáveis `${...}` da senha. Não é
necessário ativar o ambiente virtual no PowerShell.

Alternativamente, se tiver o cliente `psql` instalado, valide a conexão no
PowerShell, substituindo somente host e usuário pelos valores do painel:

```powershell
psql "host=HOST_DO_PAINEL port=5432 dbname=postgres user=postgres.PROJECT_REF sslmode=require connect_timeout=10" -W -c "\conninfo" -c "select current_database(), current_user;"
```

O parâmetro `-W` solicita a senha interativamente. O resultado esperado é o banco
`postgres` e a conexão SSL indicada por `\conninfo`. Não coloque a senha no comando. Docker e Supabase CLI
não são necessários para estes passos.

Para erros de conexão, confira: projeto ativo, host e usuário do Session pooler,
senha do banco, porta, SSL e eventuais restrições de rede configuradas no projeto.

### Falha na validação do certificado TLS

O verificador valida o certificado e o hostname mesmo quando o `.env` usa
`require` (a validação estrita corresponde a `verify-full`). Se a autoridade
certificadora não for reconhecida, baixe o certificado CA em **Database Settings →
SSL Configuration** no painel do projeto. Crie a pasta `certs` na raiz e salve o
arquivo baixado como `certs/supabase-ca.crt`, preservando seu conteúdo PEM.

Adicione ou ajuste no `.env`:

```dotenv
SUPABASE_DB_SSLMODE=verify-full
SUPABASE_DB_SSLROOTCERT=certs/supabase-ca.crt
```

Execute novamente o verificador. Ele carrega essa CA além das autoridades do
sistema e mantém a validação do hostname. A pasta `certs` é local e está ignorada
pelo Git. Se o erro persistir, confira a nova mensagem; VPN ou proxy podem
interferir na conexão, mas a presença da VPN por si só não comprova a causa.

## Próxima etapa e decisões em aberto

Após validar o acesso, siga [Banco de dados: permissões e migrações](banco-de-dados.md).
Os scripts iniciais de roles e permissões estão preparados no repositório para
execução manual, junto com as migrações das tabelas e views. A conexão do Spring
está descrita em [writer.md](writer.md); a do Flask em [reader.md](reader.md).

A autenticação escolhida é [Supabase Auth](supabase-auth.md).
Os instantes do domínio serão representados com fuso (`timestamptz`); o crédito
diário deverá seguir `America/Sao_Paulo`, conforme RF-02, independentemente do
fuso exibido na sessão. Cores e títulos por pontos exigem a V011, conforme
[personalização](personalizacao.md). Outros prêmios internos permanecem em aberto.

## Referências oficiais

- [Conexões PostgreSQL e Session pooler](https://supabase.com/docs/guides/database/connecting-to-postgres)
- [Supabase com Spring Boot](https://supabase.com/docs/guides/getting-started/quickstarts/spring-boot)
- [Configuração e desativação da Data API](https://supabase.com/docs/guides/api/securing-your-api)
- [Certificado CA e validação SSL](https://supabase.com/docs/guides/platform/ssl-enforcement)
