# Iniciar os serviços locais

Para uma máquina nova, siga primeiro a [instalação no README](../README.md#primeira-instalação).

No Windows, leia também a [limitação da edição Microsoft Store](verificacao.md):
o encerramento de descendentes pelo supervisor falhou nessa instalação. A suíte
passou no interpretador portátil oficial da mesma versão. O fluxo Linux não foi
executado nesta revisão.

Com o `.venv`, o `.env`, o JDK e as migrações até V011 já preparados, abra um
PowerShell na raiz do projeto e execute:

```powershell
.\iniciar.cmd
```

As cores e títulos por pontos exigem a [migração V011](personalizacao.md#ativar).

O comando usa o Python do `.venv` para iniciar os scripts existentes:

| Serviço | Script | Porta padrão |
| --- | --- | --- |
| Spring Boot (Gradle) | `scripts/writer.py bootRun` | 8080 |
| Flask de ranking e odds | `scripts/reader.py run` | 5000 |
| Site e proxy | `scripts/frontend.py` | 5173 |

Os logs ficam no mesmo terminal, identificados por `[Spring]`, `[Ranking]` e
`[Site]`. Aguarde **Todos os servicos estao prontos** e abra
**http://127.0.0.1:5173**. A verificação aguarda respostas HTTP; não substitui
a aplicação das migrações nem o teste de login.

Mantenha o terminal aberto. **Ctrl+C** encerra os serviços que esse comando
iniciou, incluindo o Java executado pelo Gradle. Se um serviço encerrar
inesperadamente, os demais também serão encerrados e o comando retornará erro.

## Alternativas

Para iniciar somente Spring e Flask:

```powershell
.\iniciar.cmd --backend-only
```

Para trocar a porta do site:

```powershell
.\iniciar.cmd --port 5174
```

O limite de espera inicial é de 180 segundos. Se o primeiro download do Gradle
ou das dependências precisar de mais tempo:

```powershell
.\iniciar.cmd --timeout 600
```

Também é possível executar diretamente o supervisor Python, com as mesmas opções:

```powershell
.\.venv\Scripts\python.exe scripts/dev.py
```

## Linux

Use Python 3.13 com suporte a `venv` e JDK 21, versões da verificação local. No Ubuntu/Debian,
por exemplo: `sudo apt install python3-venv openjdk-21-jdk`.
Na raiz do projeto, prepare o ambiente:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r scripts/requirements.txt -r services/reader/requirements.txt
./iniciar.sh
```

Um ambiente virtual criado no Windows não pode ser reutilizado no Linux;
recrie o `.venv` nesse sistema. Configure o `.env` e o certificado CA conforme
[configuração do Supabase](supabase.md), usando caminhos Linux para arquivos.
As migrações até V011 precisam estar aplicadas antes da inicialização.
O primeiro uso do Gradle requer acesso à internet para baixar suas dependências.

As mesmas opções estão disponíveis: `./iniciar.sh --backend-only`,
`./iniciar.sh --port 5174` e `./iniciar.sh --timeout 600`.
O script pode ser chamado de outra pasta e não exige ativar o ambiente virtual.
Ctrl+C encerra também os processos descendentes no Linux.

## Portas e rede local

O supervisor respeita `GAMBLR_WRITER_PORT`, `GAMBLR_READER_PORT` e
`GAMBLR_BIND_ADDRESS` do `.env`. As portas dos serviços devem ser diferentes.
Antes de iniciar, ele verifica as portas e recusa continuar se alguma estiver
ocupada. Não encerra processos que já estavam em execução. Pare as instâncias
antigas com Ctrl+C nos respectivos terminais antes de usar o comando único.

Para acesso pela rede local, mantenha `GAMBLR_BIND_ADDRESS=0.0.0.0` no `.env` e
abra `http://IP_DA_MAQUINA:5173` no outro computador, conforme [frontend.md](frontend.md).
O IP da máquina e as permissões do firewall continuam dependendo do ambiente.

O comando não instala dependências, não aplica migrações e não altera credenciais.
Não é necessário manter três terminais abertos. Para executar cada serviço
separadamente durante depuração, os scripts anteriores continuam disponíveis.
