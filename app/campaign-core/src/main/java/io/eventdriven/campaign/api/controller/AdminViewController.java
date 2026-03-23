package io.eventdriven.campaign.api.controller;

import io.eventdriven.campaign.api.dto.response.CampaignResponse;
import io.eventdriven.campaign.application.service.CampaignService;
import io.eventdriven.campaign.domain.entity.CampaignStats;
import io.eventdriven.campaign.domain.repository.CampaignStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.List;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@SuppressWarnings("removal")
public class AdminViewController {

    private final CampaignService campaignService;
    private final CampaignStatsRepository campaignStatsRepository;
    private final JobLauncher jobLauncher;
    private final Job aggregateParticipationJob;

    @GetMapping
    public String index(
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Model model
    ) {
        List<CampaignResponse> campaigns = campaignService.getCampaigns();
        model.addAttribute("campaigns", campaigns);

        if (date != null) {
            List<CampaignStats> stats = campaignStatsRepository.findByStatsDate(date);
            model.addAttribute("date", date);
            model.addAttribute("stats", stats);
        }
        return "admin/index"; // expects templates/admin/index.html
    }

    @PostMapping("/aggregate")
    public String aggregate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            RedirectAttributes redirect
    ) throws Exception {
        JobExecution exec = jobLauncher.run(
                aggregateParticipationJob,
                new JobParametersBuilder()
                        .addString("date", date.toString())
                        .addLong("ts", System.currentTimeMillis())
                        .toJobParameters()
        );

        redirect.addAttribute("date", date.toString());
        redirect.addFlashAttribute("jobStatus", exec.getStatus().toString());
        redirect.addFlashAttribute("exitStatus", exec.getExitStatus().getExitCode());
        return "redirect:/admin";
    }
}

