---
project: beduno-be
researched_at: 2026-08-26
recommended_platform: AWS — Amazon ECS Express Mode (Fargate) + Amazon RDS for PostgreSQL
superseded_by: single EC2 instance running docker compose — see the Addendum (2026-09-09)
runner_up: Render (Starter web service + Basic Postgres, Frankfurt)
context_type: mvp
tech_stack:
  language: Java 21
  framework: Spring Boot 3.4.4
  runtime: JVM (eclipse-temurin 21, containerised via docker/Dockerfile)
---

> **This recommendation was not the one deployed.** The research stands as written — it is a
> dated artifact and nothing in it has been edited away — but the deployment that exists is a
> single EC2 instance running docker compose. Read the **Addendum** at the end of this file
> before acting on anything below, particularly the Getting Started steps and the risk register.

## Recommendation

**Deploy on AWS using Amazon ECS Express Mode (Fargate) with Amazon RDS for PostgreSQL.**

Beduno is a containerised JVM service with a relational core — a Spring Boot 3.4 modular monolith
whose correctness depends on PostgreSQL transactions, Flyway-versioned schema and Testcontainers-backed
integration tests. That rules out every edge/function-runtime platform and narrows the field to
container PaaS. Among those, AWS wins on three answers given during the interview: the developer holds
an AWS Solutions Architect Associate certification, holds roughly $120 in AWS credits, and prefers
co-located managed services — RDS sits in the same account, VPC and bill as the compute. ECS Express
Mode is specifically the AWS abstraction that keeps this at MVP altitude: a container image plus two
IAM roles yields a Fargate service with an HTTPS URL, TLS, autoscaling and logging, with every
underlying resource still reachable through the normal ECS APIs when finer control is needed.

The decision was taken with the cost cliff understood and accepted, not overlooked. See the risk
register — item R1 is the reason this file exists.

## Platform Comparison

Seven platforms were researched. The scoring lens is the five agent-friendly platform criteria
(CLI-first maintenance, managed over raw infrastructure, agent-accessible documentation, stable
scriptable deployment API, MCP or first-class agent integration).

| Platform | CLI-first | Managed | Agent docs | Deploy API | MCP | Total |
|---|---|---|---|---|---|---|
| AWS (ECS Express Mode + RDS) | Pass | Pass | Pass | Pass | Pass | 5 |
| Fly.io | Pass | Pass | Pass | Pass | Partial | 4.5 |
| Railway | Pass | Pass | Partial | Pass | Pass | 4.5 |
| Render | Partial | Pass | Pass | Pass | Partial | 4 |
| Cloudflare (Workers + Containers) | Pass | Pass | Pass | Pass | Pass | 5 — wrong shape |
| Vercel | Pass | Pass | Pass | Pass | Partial | 4.5 — wrong shape |
| Netlify | Pass | Pass | Partial | Pass | Pass | 4.5 — wrong shape |

The criteria measure agent-operability, not JVM fitness. Three platforms therefore score well as
platforms and badly as *this project's* platform; the hard runtime and data-layer constraints are
applied on top of the score rather than read out of it.

**Per-platform notes.**

- **AWS (ECS Express Mode).** CLI-first: manageable via AWS CLI, SDKs, CloudFormation, CDK and
  Terraform. Managed: Fargate — no OS, no patching; Express Mode additionally provisions the ALB,
  TLS, autoscaling policies, monitoring and networking. Docs: AWS documentation is published in
  markdown on GitHub and is heavily represented in training data. Deploy API: the ECS API is
  versioned and stable, though the Express Mode surface on top of it is new. MCP: the official
  Express Mode documentation names the **AWS Labs MCP Server for Amazon ECS** as a supported
  management path — the strongest MCP signal in the pool because it is documented by the vendor
  rather than community-maintained.
- **Fly.io.** The best pure CLI story of all seven — `flyctl` is the canonical interface, not a
  wrapper over a dashboard — with docs published as MDX on GitHub and deterministic
  `fly deploy` / `fly releases` / `fly rollback` semantics. It scores 4th only because of the data
  layer: unmanaged Fly Postgres is deprecated and unsupported, and Fly Managed Postgres starts at
  **$38/month**, which breaks both the cost priority and the co-location preference simultaneously.
- **Railway.** Genuinely the best developer experience of the PaaS three, with a Railway MCP server
  and a Claude Code plugin, and native managed PostgreSQL. Held back by pricing — the $5 Hobby
  "plan" is $5 of usage credit at roughly $10/GB-RAM and $20/vCPU per month, so a 1 GB JVM plus
  managed Postgres lands around $20–30/month with no free tier — and by a documented run of
  outages and degraded performance in the EU-West region, including a December 2025 incident that
  paused builds across all plan tiers.
- **Render.** The cheapest credible always-on JVM with co-located Postgres: Starter web service at
  $7/month (512 MB / 0.5 vCPU) plus Basic-256mb Postgres at $6/month, in the Frankfurt region.
  CLI is scored Partial because deploy hooks and the REST API, rather than a first-class CLI, are
  the primary automation path. Its free tier is not usable here: free web services spin down after
  15 minutes idle with 30–60 second cold starts, and free Postgres expires after 30 days.
- **Cloudflare / Vercel / Netlify.** None runs a JVM in its function runtime — Cloudflare Workers
  are V8 isolates (JS/TS/WASM), Vercel Functions support Node.js, Python, Go and Ruby, Netlify
  is primarily JavaScript and Go. All three now offer container escape hatches (Cloudflare
  Containers, Vercel OCI images via the Vercel Container Registry, Netlify containers), so Java is
  not literally impossible on any of them, but none pairs that with co-located managed PostgreSQL.
  Dropped on shape, not on capability.
- **AWS App Runner — hard-dropped, not scored.** App Runner stopped accepting new customers on
  **30 April 2026** and moved to maintenance with no new features planned. It cannot be onboarded
  to today. AWS's named successor is ECS Express Mode, which is what was scored in its place. This
  is the single most decision-relevant fact the research surfaced: the obvious AWS answer for a
  containerised MVP no longer exists for new accounts.

### Shortlisted Platforms

#### 1. AWS — ECS Express Mode + RDS PostgreSQL (Recommended)

Only candidate that satisfies all four weighted interview answers at once: it uses credits already
held (cost), it runs on infrastructure the developer is certified in (familiarity), it is
single-region by default with no edge premium (geography), and RDS is co-located in the same
account and VPC (co-location). It is also the only shortlisted platform whose vendor publishes an
MCP server for the deployment surface, which matters for a project that intends to be operated by
agents. Within the credit window the out-of-pocket cost is zero.

#### 2. Render

Wins on steady-state economics and on having no cliff of any kind: roughly **$13/month** all-in for
an always-on JVM plus managed Postgres in Frankfurt, billed at a flat instance price rather than
metered compute, with no VPC, no NAT Gateway and no load-balancer line item to reason about. It
loses because it uses neither the credits nor the certification, and because its CLI story is the
weakest of the shortlist. This is the designated landing zone if R1 fires.

#### 3. Railway

Wins on iteration speed and on agent integration — MCP server plus a Claude Code plugin is the
second-best agent story after AWS. Loses on cost (no free tier; metered RAM/vCPU puts a JVM plus
Postgres at $20–30/month from day one) and on the EU-West reliability record, which is a poor fit
for an FR that requires check-in to work at the moment a worker arrives.

## Anti-Bias Cross-Check: AWS ECS Express Mode + RDS

### Devil's Advocate — Weaknesses

1. **The Application Load Balancer is a fixed ~$18/month tax on a zero-user MVP.** Express Mode's
   cost-saving pitch is amortising one ALB across up to 25 services; Beduno is one service. The
   ~$0.0225/hour base charge accrues regardless of traffic, and the PRD records `qps: low`.
2. **The credits buy roughly two months, not a runway.** Realistic eu-central-1 steady state:
   Fargate 0.5 vCPU / 1 GB ≈ $20/month (≈$16 on Graviton), ALB ≈ $18/month, RDS `db.t4g.micro`
   Single-AZ with 20 GB gp3 ≈ $16/month, CloudWatch + ECR + data transfer ≈ $4/month — about
   **$55–60/month**. Against ~$120 of credits that is roughly two months of cover.
3. **There is no RDS free tier to fall back on.** The $100-automatic-plus-$100-on-onboarding credit
   structure is the AWS Free Plan introduced for accounts created on or after 15 July 2025. Those
   accounts receive credits *instead of* the legacy 12-month, 750-hour RDS free tier. When credits
   are exhausted there is no free `db.t4g.micro` underneath.
4. **ECS Express Mode is about nine months old.** Announced at re:Invent in November 2025, GA in
   all ECS/Fargate regions, and reached AWS GovCloud in June 2026 — but it is the newest layer in
   the stack, and it is the direct replacement for a service AWS has just retired. The abstraction
   has not yet survived a full deprecation cycle.
5. **Fargate is an awkward host for a cold JVM.** Spring Boot 3.4 with Hibernate, Flyway and
   MapStruct-generated mappers needs tens of seconds to reach a serving state. Any autoscaling event
   or task replacement exposes a cold JVM to real traffic, against a non-functional constraint that
   states check-in must remain usable "at the moment a worker arrives — including late at night, on
   a phone, by staff standing at the door."

### Pre-Mortem — How This Could Fail

Six months out, the deployment is a liability. Beduno went onto ECS Express Mode in week one and it
worked exactly as advertised — one command, HTTPS, a URL. Nobody modelled the steady-state bill,
because credits made the first two months read $0.00. In month three the Free Plan window closed
while credits were already exhausted, and the account flipped to a ~$55/month invoice for an API
serving one design-partner agency and no production users. The response was to shrink the Fargate
task to 0.25 vCPU / 0.5 GB, which the JVM could not hold; the container began OOM-killing under
load the Testcontainers-sized integration suite had normalised, and nobody caught it before deploy
because there is still no CI pipeline running that suite. Meanwhile the Solutions Architect instinct
that made AWS feel easy pulled the project sideways: a VPC, private subnets, a NAT Gateway for ECR
pulls (another ~$35/month nobody budgeted), Secrets Manager, a parameter hierarchy. What was meant
to be an MVP deployment had become infrastructure with its own maintenance surface, sitting on a
codebase with 16 test files against 115 source files and nothing automating them.

### Unknown Unknowns

- **`JWT_SECRET` has a hardcoded development default.** `src/main/resources/application.yml:41`
  reads `secret: ${JWT_SECRET:beduno-dev-secret-key-that-is-at-least-256-bits-long-for-hs256}`,
  and `application-prod.yml` does not override it. A deployment that forgets the environment
  variable boots successfully and signs tokens with a key published in the repository — anyone
  could forge an access token carrying any `agencyId` and any role, which defeats every
  tenant-isolation guarantee in the system at once. There is no fail-fast on the missing variable.
  This is the single highest-severity item surfaced by the research.
- **NAT Gateway is the classic AWS bill ambush.** A Fargate task in a private subnet needs routes to
  ECR and Secrets Manager. NAT Gateway is roughly $35/month plus per-GB processing — more than the
  entire compute budget. VPC interface endpoints, or a public subnet with an assigned public IP,
  avoid it, but only as a deliberate up-front choice.
- **Flyway migrations do not roll back with the container.** Reverting an ECS deployment restores
  the image, not the schema. A deploy that applies a new `V{n}__*.sql` and is then rolled back
  leaves a newer schema under older code. The saving grace is `ddl-auto: validate`, which turns
  that into a loud startup failure rather than silent corruption — but it is still an outage.
- **Free Plan accounts are shut down, not billed, when credits reach zero.** AWS stops the account
  rather than charging the card. That is a safety feature while learning and an availability
  incident once a design partner depends on the system. Moving to a Paid plan before partner
  onboarding is a deliberate step, not a default.
- **`allowedOriginPatterns("*")` combined with `allowCredentials(true)`.** The README already flags
  this CORS configuration as unsafe for production. It is harmless against localhost and becomes a
  real vulnerability the moment the service has a public HTTPS URL — which is the first thing
  Express Mode provides.
- **The container-aware JVM defaults to a 25% max heap.** `docker/Dockerfile` ends in a bare
  `ENTRYPOINT ["java", "-jar", "app.jar"]` with no heap flags. On a 1 GB Fargate task that yields
  roughly a 256 MB maximum heap. It fits today and becomes the first thing to fail as occupancy
  exports and constraint evaluation grow.

## Operational Story

- **Preview deploys**: no preview environments in the MVP. Express Mode services are created per
  service, not per branch; a preview URL means creating a second Express Mode service pointing at a
  branch-built image tag, which adds a second Fargate task and (unless it shares the networking
  configuration, in which case it shares the ALB) a second load balancer. Recommendation for the
  4-week delivery window: one `beduno-api` service, verified locally against
  `docker compose -f docker/docker-compose.yml up -d`, with preview environments deferred until CI
  exists.
- **Secrets**: `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` and `JWT_SECRET` live in AWS
  Secrets Manager and are injected into the task definition via `secrets` (valueFrom), never as
  plaintext `environment` entries — task definitions are readable by anyone with
  `ecs:DescribeTaskDefinition`. The task execution role needs `secretsmanager:GetSecretValue` on
  exactly those secret ARNs and nothing broader. Rotation: update the secret version, then force a
  new deployment (`aws ecs update-service --force-new-deployment`) so tasks pick it up — Fargate
  tasks read secrets at start, not continuously. `JWT_SECRET` rotation invalidates every live access
  token, so rotate it during a quiet window.
- **Rollback**: `aws ecs update-service --cluster <cluster> --service beduno-api --task-definition
  <previous-revision-arn>` and the service rolls back to the prior task definition; time to revert
  is one deployment cycle, dominated by JVM start plus health-check pass — budget 2–4 minutes.
  **Data caveat**: this reverts the image only. Flyway migrations already applied stay applied, and
  `ddl-auto: validate` will refuse to start the older code against the newer schema. Any migration
  that is not additive needs a forward fix, not a rollback.
- **Approval**: an agent may build and push images to ECR, create task-definition revisions, deploy
  and roll back the `beduno-api` service, and read logs and metrics — unattended. A human must
  approve: creating or destroying the RDS instance, applying any Flyway migration that drops or
  renames a column, rotating `JWT_SECRET`, changing the account's billing plan, and any IAM policy
  change. The account-shutdown behaviour of the Free Plan makes billing changes a human decision.
- **Logs**: `aws logs tail /ecs/beduno-api --follow --since 15m` for the running service, with
  `--filter-pattern` for targeted searches; `aws ecs describe-services --cluster <cluster>
  --services beduno-api` for deployment state and rollout events; `aws ecs describe-tasks` for
  stopped-task reasons (this is where OOM kills and health-check failures surface). All read-only.
  The AWS Labs MCP Server for Amazon ECS exposes the same surface as typed tools where structured
  access is preferable to parsing CLI output.

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| **R1** — Credits exhausted in ~2 months; bill jumps to ~$55–60/month, or the Free Plan account is shut down at zero | Devil's advocate + Unknown unknowns | H | H | Set an AWS Budget alarm at $40 and a second at $90 of credit consumed, both notifying by email, on day one. Decide before the credits pass 50% whether to upgrade to a Paid plan or migrate to Render (~$13/month, documented as runner-up above). Do not let the decision arrive as an outage. |
| **R2** — `JWT_SECRET` not set in the deployed environment; app silently uses the repository's dev default and every token becomes forgeable | Research finding (`application.yml:41`) | M | Critical | Set `JWT_SECRET` from Secrets Manager in the task definition, and remove the default from `application.yml` so a missing value fails startup instead of falling back. Verify after first deploy by confirming a token signed with the dev key is rejected. |
| **R3** — ALB costs more than the application it fronts | Devil's advocate | H | M | Accepted for now — it is the price of managed TLS and health checks. If cost pressure arrives before R1 forces the issue, the ALB is the first line item to remove, which means leaving Express Mode. |
| **R4** — NAT Gateway added unnoticed for ECR/Secrets Manager egress, adding ~$35/month | Unknown unknowns | M | H | Decide the networking shape before the first deploy: either VPC interface endpoints for ECR, ECR Docker, Secrets Manager, CloudWatch Logs and S3, or a public subnet with an assigned public IP. Confirm with `aws ec2 describe-nat-gateways` after the stack is up — expect an empty list. |
| **R5** — Cold-start JVM serves a late-night check-in | Devil's advocate | M | H | Set the Express Mode minimum task count to 1 so the service never scales to zero. Point the ALB health check at `/actuator/health` (Spring Boot's default exposed endpoint — the repo sets no `management.*` overrides) with a start period long enough for Flyway plus context initialisation; the existing `docker/Dockerfile` HEALTHCHECK already assumes 30 seconds. |
| **R6** — Task shrunk to save money; JVM OOM-kills under load no CI would have caught | Pre-mortem | M | H | Do not size below 0.5 vCPU / 1 GB. Set `-XX:MaxRAMPercentage=75` in the entrypoint so the heap tracks the task size rather than defaulting to 25%. Stand up CI before the first cost-driven resize — the stack assessment already flags the absent pipeline as the weakest point in the project. |
| **R7** — Rolled-back deploy leaves newer schema under older code | Unknown unknowns | M | M | Keep migrations additive and backward-compatible for the life of the MVP: add columns nullable, never drop or rename in the same release that stops using them. `ddl-auto: validate` converts a mismatch into a startup failure rather than data loss — leave it enabled. |
| **R8** — Public HTTPS URL exposes the wide-open CORS configuration | Unknown unknowns | H | M | Replace `allowedOriginPatterns("*")` with an explicit origin list before the first deploy, or drop `allowCredentials(true)`. The two together are rejected by browsers in some configurations and are a real vulnerability in the rest. Already flagged in `README.md`. |
| **R9** — ECS Express Mode is nine months old and is itself a replacement for a retired service | Devil's advocate | L | M | Low exposure by design: Express Mode composes standard ECS, Fargate, ALB and CloudWatch resources in the account, all directly manageable. If the abstraction is withdrawn the underlying service survives; the cost would be re-wiring, not re-platforming. |

## Getting Started

Commands validated against this repository's actual configuration — Java 21, Spring Boot 3.4.4,
Gradle 8.12, the existing `docker/Dockerfile`, and the `${DATABASE_URL}` / `${DATABASE_USERNAME}` /
`${DATABASE_PASSWORD}` bindings in `application-prod.yml`.

1. **Set the budget alarms first.** Before any resource exists, create AWS Budgets alerts at $40 and
   $90 of credit consumption. R1 is the highest-likelihood risk on the register and this is the only
   mitigation that works while unattended.
2. **Close R2 and R8 in code.** Delete the `JWT_SECRET` default from `src/main/resources/application.yml:41`
   so a missing value fails startup, and narrow `allowedOriginPatterns("*")` to an explicit origin
   list. Both are one-line changes and both stop being theoretical the moment the service has a
   public URL.
3. **Provision RDS PostgreSQL 16.** A `db.t4g.micro`, Single-AZ, 20 GB gp3 instance in the chosen EU
   region, in the same VPC the service will run in, not publicly accessible. Store the JDBC URL,
   username and password in Secrets Manager alongside a freshly generated `JWT_SECRET` of at least
   256 bits.
4. **Build and push the image.** The existing multi-stage `docker/Dockerfile` (eclipse-temurin
   21-jdk-alpine to build, 21-jre-alpine to run, port 8080) needs no changes beyond the heap flag
   from R6. Build for the target architecture explicitly — if the Fargate task is Graviton, that
   means `docker buildx build --platform linux/arm64 -f docker/Dockerfile -t <ecr-uri>:<tag> .`;
   an x86 image on an ARM task fails at runtime, not at deploy. Then `aws ecr get-login-password |
   docker login` and `docker push`.
5. **Create the Express Mode service.** It needs the image URI, a task execution role (with
   `secretsmanager:GetSecretValue` scoped to the four secret ARNs) and an infrastructure role. Set
   `SPRING_PROFILES_ACTIVE=prod` in the environment, the four secrets via `valueFrom`, a minimum
   task count of 1 (R5), and 0.5 vCPU / 1 GB (R6). Express Mode returns the HTTPS URL; verify with
   `curl https://<url>/actuator/health` and confirm Flyway applied V1–V7 by tailing
   `aws logs tail /ecs/beduno-api --since 10m`.

## Out of Scope

The following were not evaluated in this research:
- Docker image configuration (the existing `docker/Dockerfile` was read to validate deployment
  steps, not redesigned)
- CI/CD pipeline setup — noted repeatedly as a prerequisite, but designing it is not this
  document's job
- Production-scale architecture: multi-region, high availability, disaster recovery, Multi-AZ RDS,
  read replicas

---

## Addendum — what was actually deployed (2026-09-09)

**Decision: one `t4g.small` EC2 instance in `eu-central-1` running the app, PostgreSQL and Caddy
under docker compose, stopped when not in use.** Not ECS Express Mode, and not RDS.

### Why the recommendation was not taken

The research answered the question it was given — the best *platform* for this application — and
answered it well. The question that actually mattered turned out to be a different one: what does
a playground with no users and no traffic need to cost? At ~$55–60/month, of which the load
balancer and the managed database are the bulk, the recommended stack spends the entire credit
balance in two months on an API that nobody is calling yet. R1 was rated the highest-likelihood
risk on the register, and the cheapest way to close it was to stop paying for the two line items
that drive it.

The single instance is roughly **$2.60/month** at a couple of hours of uptime a day, or about
$12/month left running continuously. Caddy replaces the ALB and terminates TLS with an
automatically renewed Let's Encrypt certificate; a PostgreSQL container replaces RDS; the box is
stopped when it is not in use, which is the actual cost control. Everything the research
established about the *application* — arm64 for Graviton, the heap flag, the secrets never
appearing in plaintext, no NAT Gateway — carried over unchanged.

This is a deliberate trade of resilience for cost, taken while the system has one design partner
and no production users. It is the wrong shape the moment somebody depends on it.

### What this changes in the risk register

| Risk | Status under the deployed shape |
|------|--------------------------------|
| R1 — credits exhausted in ~2 months | **Largely closed.** ~$2.60/month against ~$120 of credits is years, not weeks. Budget alerts exist ($5 monthly with 85/100% notifications, plus a zero-spend alert). |
| R2 — `JWT_SECRET` dev default | **Closed** (commit `fc14245`). No fallback outside the `dev` profile; a missing value fails startup. |
| R3 — ALB costs more than the app | **Gone.** There is no load balancer. Caddy does TLS on the instance. |
| R4 — NAT Gateway added unnoticed | **Gone.** The instance is in a public subnet with a public IP and pulls from ECR directly. |
| R5 — cold-start JVM serves a late-night check-in | **Changed, and worse.** There is no minimum task count to set: a stopped instance is a stopped API, and starting it takes minutes. Acceptable only because no operator depends on it yet. |
| R6 — task shrunk to save money, JVM OOMs, no CI to catch it | **Closed both halves.** `-XX:MaxRAMPercentage=65` tracks the compose `mem_limit`, and CI now runs the suite on pull requests and on pushes to `main`. The research said 75; a review of the deployed shape corrected it, because the percentage bounds the heap while the cgroup bounds the whole process, and metaspace, code cache and thread stacks add roughly 250 MB on top. Exceeding the cgroup is a SIGKILL, not an OutOfMemoryError. |
| R7 — rollback leaves newer schema under older code | **Unchanged.** Still `ddl-auto: validate`, still a loud failure rather than corruption, still needs additive migrations. Documented in the README rollback section. |
| R8 — public URL exposes wide-open CORS | **Closed** (commit `fc14245`). Explicit allowlist; empty registers no mapping at all. |
| R9 — ECS Express Mode is nine months old | **Gone.** Nothing here depends on it. |

### Risks this shape introduces

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| **N1** — the instance is a single point of failure for compute *and* data; losing the volume loses every row | L | Critical | `deploy/backup.sh` snapshots the volume; `instance.sh stop` snapshots before stopping; `backup.sh enable-daily` installs a DLM policy that snapshots even while the box is off. Restore is documented and manual. |
| **N2** — no managed-database guarantees: no point-in-time recovery, no automated failover, no replica | M | H | Accepted at this stage. Snapshots are crash-consistent, so recovery replays the WAL as after a power cut. Moving to RDS is the upgrade path when a partner depends on the data. |
| **N3** — DuckDNS is a free third-party dependency in the TLS path; if it stops resolving, certificate renewal fails and the hostname dies | L | H | Cheap to replace with a real domain: it is one hostname in one SSM parameter and the Caddy site address. Certificates last 90 days, so an outage has a long fuse. |
| **N4** — Let's Encrypt rate limits against a box that restarts by design | L | M | The `caddy_data` volume persists certificates across restarts; only a volume loss re-requests one. |
| **N5** — the deploy path is a shell script run from a laptop, not a pipeline | M | M | `publish.sh` refuses a dirty tree and tags with the commit sha, so what is deployed is always identifiable. CI runs the tests but does not deploy; wiring it to deploy needs an OIDC role and is not done. |
| **N6** — `docker compose pull` on a 2 GB box while the old containers are still running | L | M | The compose memory limits leave ~600 MB of headroom, and images are pulled before the swap. Watch it on the first few deploys. |

### Still open from the research

- **Preview environments** — none, as recommended. Verification is local plus CI.
- **Production-scale architecture** — multi-region, HA, DR, Multi-AZ, read replicas: all still out
  of scope, and now further out of reach than the recommended shape would have left them.
- **Per-property scoping (S1) and role-restricted overrides (S2)** remain unfixed. They are the
  largest known security gaps, and unlike S3/S4/S5/S7/S8 they were not closed before this
  deployment.
