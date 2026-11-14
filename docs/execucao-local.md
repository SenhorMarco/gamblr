# Iniciar os serviços locais

Com o `.venv`, o `.env`, o JDK e as migrações até V008 já preparados, abra um
PowerShell na raiz do projeto e execute:

```powershell
.\iniciar.cmd
```

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

Em outros sistemas, utilize o Python do ambiente virtual para executar `scripts/dev.py`.

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
