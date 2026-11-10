package com.example.demo_agent.controller;

import com.example.demo_agent.eval.RuntimeResponseEvaluator;
import com.example.demo_agent.eval.RuntimeResponseEvaluator.RuntimeEvalSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "support.runtime-eval", name = "enabled", havingValue = "true")
public class RuntimeEvalDashboardController {

	private final RuntimeResponseEvaluator evaluator;

	public RuntimeEvalDashboardController(RuntimeResponseEvaluator evaluator) {
		this.evaluator = evaluator;
	}

	@GetMapping("/eval-results")
	public RuntimeEvalSnapshot results() {
		return evaluator.snapshot();
	}

	@GetMapping(value = "/eval-dashboard", produces = MediaType.TEXT_HTML_VALUE)
	public String dashboard() {
		return """
				<!doctype html><html lang="en"><head><meta charset="utf-8">
				<meta name="viewport" content="width=device-width,initial-scale=1">
				<title>Runtime LLM evals</title><style>
				body{font-family:system-ui,sans-serif;margin:0;background:#f6f7fb;color:#172033}
				main{max-width:1150px;margin:32px auto;padding:0 20px}.muted{color:#64748b}
				.cards{display:grid;grid-template-columns:repeat(6,1fr);gap:12px;margin:22px 0}
				.card{background:#fff;border-radius:10px;padding:14px;box-shadow:0 1px 3px #0001}
				.card b{display:block;font-size:24px}.bad b{color:#b42318}.good b{color:#067647}
				table{width:100%;border-collapse:collapse;background:#fff;box-shadow:0 1px 3px #0001}
				th,td{text-align:left;padding:11px;border-bottom:1px solid #e5e7eb;vertical-align:top}
				tr.failed{background:#fff1f0}tr.error{background:#fff7ed}tr.passed{background:#f0fdf4}
				@media(max-width:800px){.cards{grid-template-columns:repeat(2,1fr)}.answer{display:none}}
				</style></head><body><main><h1>Runtime LLM evals</h1>
				<p class="muted">Asynchronous sampled grading · refreshes every 2 seconds</p>
				<div id="cards" class="cards"></div><table><thead><tr><th>Status</th><th>Question</th>
				<th class="answer">Answer</th><th>Scores</th><th>Explanation</th></tr></thead><tbody id="rows"></tbody></table>
				<script>
				const esc=v=>String(v??'').replace(/[&<>\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
				async function refresh(){try{const r=await fetch('/eval-results',{cache:'no-store'});const d=await r.json();
				const passed=d.retained-d.failed-d.errors;const vals=[['Observed',d.observed,''],['Sampled',d.sampled,''],
				['Passed',passed,'good'],['Failed',d.failed,'bad'],['Errors',d.errors,'bad'],['Queued',d.queued,'']];
				document.getElementById('cards').innerHTML=vals.map(x=>`<div class="card ${x[2]}"><span>${x[0]}</span><b>${x[1]}</b></div>`).join('');
				document.getElementById('rows').innerHTML=d.results.map(x=>{const g=x.grade;
				const scores=g?`C ${g.correctness} / G ${g.groundedness} / H ${g.helpfulness} / ${g.policyCompliance}`:'-';
				return `<tr class="${x.status.toLowerCase()}"><td><b>${esc(x.status)}</b></td><td>${esc(x.question)}</td>`+
				`<td class="answer">${esc(x.answer)}</td><td>${esc(scores)}</td><td>${esc(x.error||(g&&g.explanation)||'')}</td></tr>`}).join('');
				}catch(e){console.error(e)}}refresh();setInterval(refresh,2000);
				</script></main></body></html>
				""";
	}
}
