import { http, HttpResponse, type JsonBodyType } from "msw";
import { MockHttpError, type MockStore } from "./store";

type Params = Record<string, string>;

const api = (path: string) => `*/api${path}`;

/** Runs a store call and maps its result or MockHttpError to a response. Results may carry their own status. */
async function respond(work: () => unknown, status = 200) {
  try {
    const result = await work();
    if (result === undefined) return new HttpResponse(null, { status: 204 });
    if (result && typeof result === "object" && "status" in result && "body" in result && typeof result.status === "number") {
      return HttpResponse.json(result.body as JsonBodyType, { status: result.status });
    }
    return HttpResponse.json(result as JsonBodyType, { status });
  }
  catch (error) {
    if (error instanceof MockHttpError) {
      return HttpResponse.json({ code: error.code, message: error.message }, { status: error.status });
    }
    throw error;
  }
}

async function json(request: Request): Promise<Record<string, unknown>> {
  try {
    return (await request.json()) as Record<string, unknown>;
  }
  catch {
    return {};
  }
}

/** Every contract endpoint (docs/api/frontend-api-contract.md). */
export function createHandlers(store: MockStore) {
  const pair = "/resumes/:resumeId/target-jobs/:targetJobId";
  return [
    http.get(api("/jobs/:jobId"), ({ params }) => respond(() => store.getJob((params as Params).jobId))),

    http.post(api("/resumes"), async ({ request }) => {
      const form = await request.formData();
      const file = form.get("file");
      // Not `instanceof File`: jsdom and the fetch implementation each have their own File class.
      if (!file || typeof file === "string") {
        return HttpResponse.json({ code: "INVALID_REQUEST", message: "file is required" }, { status: 400 });
      }
      const content = await file.text();
      return respond(() => store.uploadResume({
        fileName: file.name,
        size: file.size,
        content,
        name: form.get("name") as string | null,
        jobTitle: form.get("jobTitle") as string | null
      }));
    }),
    http.post(api("/resumes/paste"), async ({ request }) => {
      const body = await json(request);
      return respond(() => store.pasteResume(body));
    }),
    http.get(api("/resumes"), () => respond(() => store.listResumes())),
    http.get(api("/resumes/:id"), ({ params }) => respond(() => store.getResume((params as Params).id))),
    http.patch(api("/resumes/:id"), async ({ params, request }) => {
      const body = await json(request);
      return respond(() => store.updateResume((params as Params).id, body));
    }),
    http.get(api("/resumes/:id/delete-impact"), ({ params }) => respond(() => store.resumeImpact((params as Params).id))),
    http.delete(api("/resumes/:id"), ({ params }) => respond(() => store.deleteResume((params as Params).id))),
    http.post(api("/resumes/:id/score"), ({ params }) => respond(() => store.scoreResume((params as Params).id), 202)),

    http.post(api("/target-jobs"), async ({ request }) => {
      const body = await json(request);
      return respond(() => store.createTargetJob(body));
    }),
    http.get(api("/target-jobs"), () => respond(() => store.listTargetJobs())),
    http.get(api("/target-jobs/:id"), ({ params }) => respond(() => store.getTargetJob((params as Params).id))),
    http.patch(api("/target-jobs/:id"), async ({ params, request }) => {
      const body = await json(request);
      return respond(() => store.renameTargetJob((params as Params).id, body.name));
    }),
    http.get(api("/target-jobs/:id/delete-impact"), ({ params }) => respond(() => store.targetJobImpact((params as Params).id))),
    http.delete(api("/target-jobs/:id"), ({ params }) => respond(() => store.deleteTargetJob((params as Params).id))),

    http.post(api("/experiences"), async ({ request }) => {
      const body = await json(request);
      return respond(() => store.createExperience(body));
    }),
    http.post(api("/experiences/linkedin-split"), async ({ request }) => {
      const body = await json(request);
      return respond(() => store.splitLinkedIn(body.text), 202);
    }),
    http.post(api("/experiences/batch"), async ({ request }) => {
      const body = await json(request);
      return respond(() => store.saveExperienceBatch(body.items), 201);
    }),
    http.get(api("/experiences"), () => respond(() => store.listExperiences())),
    http.patch(api("/experiences/:id"), async ({ params, request }) => {
      const body = await json(request);
      return respond(() => store.renameExperience((params as Params).id, body.title));
    }),
    http.get(api("/experiences/:id/delete-impact"), ({ params }) => respond(() => store.experienceImpact((params as Params).id))),
    http.delete(api("/experiences/:id"), ({ params }) => respond(() => store.deleteExperience((params as Params).id))),

    http.get(api(`${pair}/fit`), ({ params }) => {
      const { resumeId, targetJobId } = params as Params;
      return respond(() => store.getFit(resumeId, targetJobId));
    }),
    http.post(api(`${pair}/fit`), ({ params }) => {
      const { resumeId, targetJobId } = params as Params;
      return respond(() => store.runFit(resumeId, targetJobId), 202);
    }),
    http.get(api(`${pair}/suggestions`), ({ params }) => {
      const { resumeId, targetJobId } = params as Params;
      return respond(() => store.getSuggestions(resumeId, targetJobId));
    }),
    http.post(api(`${pair}/suggestions`), ({ params }) => {
      const { resumeId, targetJobId } = params as Params;
      return respond(() => store.runSuggestions(resumeId, targetJobId), 202);
    }),

    http.post(api("/practice-sets"), async ({ request }) => {
      const body = await json(request);
      return respond(() => store.createPracticeSet(body));
    }),
    http.get(api("/practice-sets/:id"), ({ params }) => respond(() => store.getPracticeSet((params as Params).id))),
    http.post(api("/practice-sets/:id/retry"), ({ params }) => respond(() => store.retryPracticeSet((params as Params).id), 202)),
    http.post(api("/practice-sets/:id/questions"), async ({ params, request }) => {
      const body = await json(request);
      return respond(() => store.addQuestion((params as Params).id, body.text), 201);
    }),
    http.post(api("/practice-sets/:id/questions/:questionId/attempts"), async ({ params, request }) => {
      const { id, questionId } = params as Params;
      const body = await json(request);
      return respond(() => store.submitAttempt(id, questionId, body.text), 201);
    }),
    http.post(api("/attempts/:id/retry"), ({ params }) => respond(() => store.retryAttempt((params as Params).id), 202)),

    http.post(api("/voice-sessions"), async ({ request }) => { const body = await json(request); return respond(() => store.createVoiceSession(body.practiceSetId), 201); }),
    http.get(api("/voice-sessions/:id"), ({ params }) => respond(() => store.getVoiceSession((params as Params).id))),
    http.post(api("/voice-sessions/:id/save"), async ({ params, request }) => { const body = await json(request); return respond(() => store.saveVoiceSession((params as Params).id, body)); }),
    http.post(api("/voice-sessions/:id/report/retry"), ({ params }) => respond(() => store.retryVoiceReport((params as Params).id), 202)),
    http.delete(api("/voice-sessions/:id/draft"), ({ params }) => respond(() => store.deleteVoiceSession((params as Params).id, true))),
    http.delete(api("/voice-sessions/:id"), ({ params }) => respond(() => store.deleteVoiceSession((params as Params).id))),
    http.post(api("/voice-sessions/:id/tokens"), () => HttpResponse.json({ code: "VOICE_TOKEN_UNAVAILABLE", message: "Voice requires a real configured API. Continue by typing." }, { status: 503, headers: { "Cache-Control": "no-store" } })),

    http.get(api("/history"), () => respond(() => store.history()))
  ];
}
