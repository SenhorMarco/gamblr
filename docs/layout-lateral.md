# Layout lateral

O layout aprovado usa a
[referência visual fornecida](https://img.odcdn.com.br/wp-content/uploads/2020/08/20200821092335.jpg)
para organizar navegação e grupos numa faixa compacta à esquerda e amigos
numa faixa à direita. O conteúdo principal e as apostas ficam entre as duas
laterais. Essa organização é a única disponível na aplicação.

## Comportamento por tamanho de tela

- Acima de 1100 px: menu à esquerda, conteúdo central, apostas e contatos à direita.
- Entre 821 e 1100 px: menu e contatos permanecem nas laterais; apostas ficam
  abaixo do conteúdo central para preservar a largura de leitura.
- Até 820 px: os amigos ficam no menu. No celular, grupos e amigos permanecem
  acima da conversa.

As laterais maiores usam posicionamento sticky abaixo do cabeçalho e rolagem
independente quando necessário. Os contatos utilizam os amigos e pedidos já
existentes.

[style.css](../frontend/static/style.css) contém o layout responsivo.
[sidebars.js](../frontend/static/sidebars.js) reposiciona os mesmos elementos de
amigos, pedidos e navegação privada conforme a largura da tela. Seus eventos e
estado são mantidos; marcadores guardam as posições usadas no celular.
O foco de teclado é restaurado se o elemento focado for movido.

## Verificação

`frontend/e2e/sidebars.spec.js` verifica posições das colunas, larguras de
1440, 1024, 820 e 390 px, foco de teclado, abertura do chat privado e
preservação de rascunhos ao redimensionar.

Com o frontend local em execução, na pasta `frontend`:

```powershell
.\node_modules\.bin\playwright.cmd test
```
