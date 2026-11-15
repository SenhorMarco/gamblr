# Design da interface

O design aprovado em 15/11/2026 é a única aparência da aplicação. Ele mantém a
identidade azul e a fonte Arial, com sombras discretas, fundos suaves,
hierarquia tipográfica, balões de mensagem e controles com foco mais claro.
Os textos de orientação foram incorporados diretamente aos componentes.

## Organização

[style.css](../frontend/static/style.css) reúne a base, o acabamento e a
organização responsiva das laterais. O HTML carrega uma única folha de estilos;
a aplicação utiliza o design aprovado em todas as telas.

O chat aproxima visualmente mensagens adjacentes do mesmo autor, enviadas em
até cinco minutos no mesmo dia. Mensagens apagadas e eventos de apostas
interrompem o agrupamento. Cada mensagem mantém seu conteúdo, autor, horário
e ações. Essa regra está em
[message-layout.js](../frontend/static/message-layout.js).

As cores de nome e título escolhidas pelos integrantes são preservadas.
Rótulos de ações, preços, regras e mensagens financeiras mantêm seu conteúdo.
O design utiliza CSS e JavaScript nativos, sem dependências adicionais.
Veja também [layout lateral](layout-lateral.md).

## Verificação

Validação em 15/11/2026: 21 testes JavaScript e 9 testes de navegador aprovados.

`frontend/e2e/appearance.spec.js` verifica mensagens, horários, ações, cores
personalizadas, controles e textos do design aprovado, em desktop e celular.
`frontend/tests/message-layout.test.js` verifica os limites do agrupamento
visual das mensagens. A suíte mantém os testes de navegação, apostas,
personalização e administração.

Na pasta `frontend`, com o site local em execução:

```powershell
npm.cmd test
.\node_modules\.bin\playwright.cmd test
```
