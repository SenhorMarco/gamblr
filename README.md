# Gamblr

Protótipo de apostas de grupo com pontos virtuais, PostgreSQL no Supabase,
Spring Boot para escritas e Flask para ranking e odds.

Para iniciar Spring, Flask e o site juntos, com o `.env` configurado e as
migrações até V009 aplicadas, execute na raiz do projeto:

```powershell
.\iniciar.cmd
```

No Linux, prepare o ambiente conforme [execução local](docs/execucao-local.md#linux)
e execute `./iniciar.sh`.

Abra **http://127.0.0.1:5173** quando os serviços estiverem prontos. Ctrl+C encerra
todos os processos iniciados pelo comando. Pare instâncias antigas antes de usar.
Para iniciar somente os dois backends: `.\iniciar.cmd --backend-only`.
Detalhes e alternativas em [execução local](docs/execucao-local.md).

- [Requisitos de produto](docs/requisitos.md)
- [Configuração do Supabase](docs/supabase.md)
- [Iniciar todos os serviços com um comando](docs/execucao-local.md)
- [Permissões e aplicação das migrações](docs/banco-de-dados.md)
- [Modelo inicial e regras ainda não implementadas](docs/modelo-de-dados.md)
- [Serviço Spring Boot com Gradle](docs/writer.md)
- [Configuração do Supabase Auth](docs/supabase-auth.md)
- [Demonstração de aposta com dois usuários](docs/demonstracao.md)
- [Flask: ranking e odds](docs/reader.md)
- [Frontend temporário para testes](docs/frontend.md)
- [Chats de grupo, amizades e mensagens privadas](docs/chats.md)
- [Resultado, contestação e pagamento das apostas](docs/resultados.md)
- [Administração, convites e crédito diário](docs/administracao.md)

O repositório contém o banco, seus verificadores e o serviço Spring Boot para
grupos, publicação e reserva de palpites, além do Flask para ranking e odds.
A interface temporária permite testar login, grupos, apostas, reservas, ranking
e odds, além de chats de grupo, pedidos de amizade e mensagens privadas pelo
navegador, incluindo resultado, contestação, cancelamento e pagamento. Chats
exigem V007, o ciclo de resultado exige V008 e a administração/crédito diário
exigem V009. A autenticação usa Supabase Auth; os serviços validam os
JWTs do projeto. A arquitetura definitiva do frontend permanece em aberto.

Credenciais ficam no `.env` local; use `.env.example` como referência sem
sobrescrever configurações existentes.
