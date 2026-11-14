export class ClientError extends Error {
  constructor(message, status = 0, code = '') { super(message); this.status = status; this.code = code; }
}
const messages = {
  invalid_credentials: 'E-mail ou senha inválidos.', email_not_confirmed: 'Confirme seu e-mail antes de entrar.',
  signup_disabled: 'O cadastro está desativado neste projeto.', weak_password: 'A senha não atende à política do projeto.',
  over_email_send_rate_limit: 'Limite de envio atingido. Tente mais tarde ou use uma conta de teste confirmada.',
  email_address_not_authorized: 'O envio de e-mail está restrito neste projeto. Use uma conta de teste confirmada.',
  user_already_exists: 'Essa conta já existe. Use a opção Entrar.',
};
export class AuthClient {
  constructor(config, {storage = globalThis.sessionStorage, fetcher = (...args) => globalThis.fetch(...args), now = () => Date.now()} = {}) {
    this.url = config.supabaseUrl; this.key = config.publishableKey; this.storage = storage; this.fetcher = fetcher; this.now = now;
    this.generation = 0; this.refreshing = null; this.onChange = () => {};
    try { const stored = JSON.parse(storage.getItem('gamblr.session')); this.session = stored?.access_token && stored?.refresh_token ? stored : null; } catch { this.session = null; }
  }
  save(session) {
    this.session = session ? {access_token: session.access_token, refresh_token: session.refresh_token,
      expires_at: session.expires_at || Math.floor(this.now()/1000)+(session.expires_in || 3600), user: session.user} : null;
    if (this.session) this.storage.setItem('gamblr.session', JSON.stringify(this.session)); else this.storage.removeItem('gamblr.session');
    this.onChange(this.session);
  }
  clear() { this.generation++; this.save(null); }
  async request(path, payload, accessToken) {
    let response;
    try { response = await this.fetcher(`${this.url}/auth/v1/${path}`, {method:'POST', headers:{'Content-Type':'application/json', apikey:this.key,
      ...(accessToken ? {Authorization:`Bearer ${accessToken}`} : {})}, ...(payload ? {body:JSON.stringify(payload)} : {})}); }
    catch { throw new ClientError('Não foi possível conectar à autenticação. Confira sua conexão.'); }
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new ClientError(messages[data.error_code] || 'Não foi possível autenticar. Confira os dados e tente novamente.', response.status, data.error_code);
    return data;
  }
  async signIn(email, password) { const session = await this.request('token?grant_type=password', {email,password}); this.generation++; this.save(session); return session; }
  async signUp(email, password, name) { const data = await this.request('signup', {email,password,data:{display_name:name}}); if(data.access_token) {this.generation++;this.save(data);} return data; }
  async refresh() {
    if (this.refreshing) return this.refreshing;
    if (!this.session) throw new ClientError('Entre novamente para continuar.',401);
    const generation = this.generation, refreshToken = this.session.refresh_token;
    this.refreshing = (async () => {
      try { const session = await this.request('token?grant_type=refresh_token', {refresh_token:refreshToken});
        if(generation !== this.generation) throw new ClientError('A sessão foi encerrada.',401);
        this.save(session); return session.access_token;
      } catch(error) { if(generation === this.generation && [400,401,403].includes(error.status)) this.clear(); throw error; }
      finally { this.refreshing = null; }
    })();
    return this.refreshing;
  }
  async token() { if(!this.session) throw new ClientError('Entre para continuar.',401); return this.session.expires_at*1000-this.now()<30000 ? this.refresh() : this.session.access_token; }
  async logout() { const token=this.session?.access_token; this.clear(); if(token) await this.request('logout?scope=local',null,token); }
}
