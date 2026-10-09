import { Check } from "lucide-react";
import { Link, linkOptions } from "@tanstack/react-router";
import { cn } from "@/lib/utils";

type Props = { current: 1 | 2 | 3 | 4 | 5 | 6; resumeId?: string; targetJobId?: string; practiceSetId?: string };

/** The six-step journey. Steps whose prerequisite ID is missing are disabled. */
export function Stepper({ current, resumeId, targetJobId, practiceSetId }: Props) {
  const pair = resumeId && targetJobId ? linkOptions({ to: "/flow/$resumeId/jobs/$targetJobId", params: { resumeId, targetJobId } }) : null;
  const steps = [
    { label: "Resume", link: linkOptions({ to: "/flow" }) },
    { label: "Score", link: resumeId ? linkOptions({ to: "/flow/$resumeId", params: { resumeId } }) : null },
    { label: "Target job", link: resumeId ? linkOptions({ to: "/flow/$resumeId/jobs", params: { resumeId } }) : null },
    { label: "Job fit", link: pair },
    { label: "Mode", link: pair },
    { label: "Practice", link: practiceSetId ? linkOptions({ to: "/practice/$setId", params: { setId: practiceSetId } }) : null }
  ];
  return (
    <nav aria-label="Progress" className="mb-8">
      <p className="text-sm font-medium text-muted-foreground md:hidden">
        Step {current} of 6: {steps[current - 1].label}
      </p>
      <ol className="hidden items-center gap-2 md:flex">
        {steps.map((step, index) => {
          const number = index + 1;
          const done = number < current;
          const isCurrent = number === current;
          const badge = (
            <span className={cn(
              "flex size-6 items-center justify-center rounded-full border text-xs",
              isCurrent && "border-primary bg-primary text-primary-foreground",
              done && "border-primary text-primary"
            )}>
              {done ? <Check className="size-3" aria-hidden /> : number}
            </span>
          );
          const content = <>{badge}<span>{step.label}</span></>;
          const className = cn("flex items-center gap-2 text-sm", isCurrent ? "font-medium" : "text-muted-foreground");
          return (
            <li key={step.label} className="flex items-center gap-2">
              {step.link && !isCurrent ? (
                <Link {...step.link} className={cn(className, "hover:text-foreground")}>{content}</Link>
              ) : (
                <span className={cn(className, !step.link && "opacity-50")} aria-current={isCurrent ? "step" : undefined} aria-disabled={!step.link || undefined}>
                  {content}
                </span>
              )}
              {number < 6 ? <span className="h-px w-6 bg-border" aria-hidden /> : null}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
