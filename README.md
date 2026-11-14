# Gamblr

Protótipo de apostas de grupo com pontos virtuais, PostgreSQL no Supabase,
Spring Boot para escritas e Flask para ranking e odds.

- [Requisitos de produto](docs/requisitos.md)
- [Configuração do Supabase](docs/supabase.md)
- [Permissões e aplicação das migrações](docs/banco-de-dados.md)
- [Modelo inicial e regras ainda não implementadas](docs/modelo-de-dados.md)
- [Serviço Spring Boot com Gradle](docs/writer.md)
- [Configuração do Supabase Auth](docs/supabase-auth.md)
- [Demonstração de aposta com dois usuários](docs/demonstracao.md)
- [Flask: ranking e odds](docs/reader.md)
- [Frontend temporário para testes](docs/frontend.md)

O repositório contém o banco, seus verificadores e o serviço Spring Boot para
grupos, publicação e reserva de palpites, além do Flask para ranking e odds.
A interface temporária permite testar login, grupos, apostas, reservas, ranking
e odds pelo navegador. A autenticação usa Supabase Auth; os serviços validam os
JWTs do projeto. A arquitetura definitiva do frontend permanece em aberto.

Credenciais ficam no `.env` local; use `.env.example` como referência sem
sobrescrever configurações existentes.
