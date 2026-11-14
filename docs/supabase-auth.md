# Supabase Auth no Gamblr

Decisão confirmada: cadastro/login serão gerenciados pelo Supabase Auth. O Spring
valida o token de usuário e aplica as regras de grupos e apostas. O banco de domínio
continua no schema privado `gamblr`; a Data API pode permanecer desativada.

Não há nova migração nesta etapa: usamos a tabela `user_identities` da V005.
Não precisamos de senha administrativa do Auth, JWT secret ou chave `service_role`
para validar os tokens.

## Configuração do projeto

No `.env` local, mantenha as credenciais de banco e acrescente:

```dotenv
SUPABASE_URL=https://SEU_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=CHAVE_PUBLICA_DO_PAINEL
```

Use a **Project URL** do projeto e a chave **publishable**, disponível em
**Project Settings → API Keys**. O verificador também aceita uma chave pública
`anon` legada. Nunca use `secret`/`service_role` nesse campo. Não sobrescreva o
`.env` com o modelo. A URL foi preenchida neste ambiente a partir do identificador
do projeto; a chave pública foi preenchida pelo usuário.

O Spring deriva o issuer como `SUPABASE_URL/auth/v1` e busca as chaves públicas em
`SUPABASE_URL/auth/v1/.well-known/jwks.json`. Deixe `GAMBLR_AUTH_ISSUER` vazio; se
for preenchido, deve coincidir com o issuer derivado. A chave publishable é usada
pelo cliente de Auth; o Spring não precisa dela para validar assinaturas.

Em **Authentication**, confira o provedor **Email** em **Sign In / Providers**.
Nesta implementação inicial usamos email/senha. Login social, MFA e a interface
de recuperação de senha ficam para outras etapas.

O serviço aceita assinaturas assimétricas **ES256** e **RS256**. O projeto atual
foi consultado e publica uma chave ES256; não foi necessário alterar nem rotacionar
suas chaves. Em um projeto legado que só use HS256, prepare a migração para chaves
assimétricas pelo painel antes de usar esta implementação. Não exporte chaves privadas.

## Verificar sem criar conta

Da raiz do repositório:

```powershell
.\.venv\Scripts\python.exe scripts/supabase_auth.py check
```

O verificador consulta a JWKS pública e, com a chave preenchida, as configurações
de Auth. Confere a disponibilidade das chaves e do provedor Email. Não cadastra
usuários, não envia emails e não altera dados. Esse teste não substitui um login
real, pois não verifica email/senha de um usuário.

## Preparar uma conta de teste

Para testar o login sem depender de entrega de email, abra **Authentication →
Users → Add user → Create new user** e crie uma conta de desenvolvimento com
email e senha. Se disponível, marque **Auto Confirm User** somente para essa conta
de teste. Essa senha é da conta do Gamblr; não reutilize a senha do banco.

Para exercitar o cadastro pelo Auth em vez da criação manual:

```powershell
.\.venv\Scripts\python.exe scripts/supabase_auth.py signup
```

O script solicita email, senha e nome interativamente, então chama o endpoint de
cadastro. Com confirmação habilitada, confirme o email antes de fazer login.
Não alteramos a confirmação global de emails no painel. O serviço de email padrão
do Supabase tem restrições de destinatários e limites: configure SMTP para testar
cadastros reais com outros endereços. A criação manual de uma conta confirmada é
apenas um recurso da demonstração, não a implementação final do cadastro do site.

Sem frontend de confirmação, o redirecionamento padrão após clicar no email pode
abrir uma página local indisponível. Para esta etapa, confira o estado de confirmação
em **Users** e use o login por senha. O frontend temporário já permite login,
mas os URLs de redirecionamento e o fluxo completo de confirmação ainda precisam
de configuração no painel para novos cadastros.

## Testar login e vínculo com o Spring

Em um terminal, inicie o Spring da raiz:

```powershell
.\.venv\Scripts\python.exe scripts/writer.py bootRun
```

Em outro terminal, também na raiz:

```powershell
.\.venv\Scripts\python.exe scripts/supabase_auth.py login --profile
```

Informe o email e a senha da conta de teste. O script autentica no Supabase e envia
o access token recebido a `POST /api/me` do Spring local. Solicita também o nome
de exibição. A senha é digitada sem eco; senha, access token e refresh token não
são impressos nem gravados em arquivo.

O resultado esperado é:

```text
Login no Supabase Auth validado.
Token aceito pelo Spring; perfil interno criado ou recuperado com sucesso.
Sessao de verificacao encerrada. Senha e tokens nao foram gravados.
```

`--profile` grava o perfil e seu vínculo de identidade em `gamblr`, se ainda não
existirem. Repetir a operação recupera o mesmo perfil. Para testar somente o login,
sem provisionar o perfil no Spring, omita `--profile`.

O script descarta os tokens e encerra a sessão de teste no Auth ao final. É um
verificador, não um cliente persistente do Gamblr. O encerramento de uma sessão
não implica revogação instantânea de access tokens já emitidos: a validação local
do Spring respeita a validade do JWT. O [frontend temporário](frontend.md) mantém
sessões separadas por aba e implementa renovação e saída pelo navegador.

## O que o Spring verifica

- Assinatura ES256/RS256 usando a chave pública do projeto.
- Emissor exato e validade temporal, incluindo a tolerância padrão de relógio da biblioteca.
- Audiência `authenticated`, papel `authenticated` e subject preenchido.
- Identidade interna a partir de issuer/subject; não aceita ID de usuário escolhido pelo cliente.
- Participação e permissões do usuário nos grupos ao executar as operações.

Tokens de outro projeto, assinatura inválida, audiência diferente ou papel
`anon`/`service_role` são rejeitados. Não adicionamos grants para `authenticated`
no schema `gamblr`: a role do token identifica uma sessão do Auth, enquanto a
conexão PostgreSQL do Spring continua usando `gamblr_writer`.

## Testes automatizados

O build Gradle executa testes locais para assinatura ES256/RS256 e rejeição de
emissor, audiência, papel, subject e validade incorretos. Os testes de integração
do writer usam emissor local de teste e schema PostgreSQL temporário, sem criar
contas no Supabase Auth real.

```powershell
.\.venv\Scripts\python.exe scripts/writer.py build
.\.venv\Scripts\python.exe scripts/writer.py integration
.\.venv\Scripts\python.exe -m unittest discover -s scripts/tests -v
```

O login real precisa ser executado pelo usuário em terminal interativo. Não envie
email/senha nem tokens pelo chat.

## Referências oficiais

- [Autenticação por senha](https://supabase.com/docs/guides/auth/passwords)
- [JWKS e validação dos tokens](https://supabase.com/docs/guides/auth/jwts)
- [Chaves de assinatura](https://supabase.com/docs/guides/auth/signing-keys)
- [Chaves públicas de API](https://supabase.com/docs/guides/getting-started/api-keys)
- [Configuração de SMTP](https://supabase.com/docs/guides/auth/auth-smtp)
