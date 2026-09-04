package ninja.samryecroft.returnhome.tracker.export;

import java.time.LocalDate;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentity;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Roadmap 2.5: the Thymeleaf screen for the child case-file export. Deliberately thin - every rule
 * (scope, fail-closed, the blocked two-step, not-persisted single-use links) lives in
 * {@link ExportController}/{@link CaseFileExportService}, called here as plain in-process Java
 * methods rather than re-implemented or re-requested over HTTP. This is the FE half of the T56
 * split: the screen, not the domain.
 */
@Controller
public class CaseFileExportPageController {

    private final ChildRepository childRepository;
    private final ExportController exportController;
    private final NameRevealService nameRevealService;

    public CaseFileExportPageController(ChildRepository childRepository, ExportController exportController,
            NameRevealService nameRevealService) {
        this.childRepository = childRepository;
        this.exportController = exportController;
        this.nameRevealService = nameRevealService;
    }

    @GetMapping("/children/{id}/export")
    public String form(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        // manifest() authorizes (capability + canViewHome) before returning anything, so the child
        // is only fetched for display once that has already succeeded.
        ExportManifest manifest = exportController.manifest(id, null, null, principal);
        Child child = childRepository.findDetailedById(id).orElseThrow();
        model.addAttribute("child", child);
        // These screens are ordinary screens, masked like any other - decision 5 ("exports are
        // never masked") is about the STATUTORY DOCUMENT, not the HTML pages that request or
        // confirm one (Kevin's review: worth stating explicitly so nobody reads that decision as
        // exempting this template).
        model.addAttribute("childIdentity", ChildIdentity.of(child, nameRevealService.isRevealed()));
        model.addAttribute("manifest", manifest);
        model.addAttribute("purposes", ExportPurpose.values());
        return "export/case-file-form";
    }

    @PostMapping("/children/{id}/export")
    public String export(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam ExportPurpose purpose, @RequestParam(required = false) String reference,
            @RequestParam(defaultValue = "ALL") String period,
            @RequestParam(required = false) Set<Long> acknowledgeBlocked,
            @RequestParam(required = false) String protect,
            Model model) {
        LocalDate from = periodFrom(period);
        // Checked (the default) sends "protect"; the browser omits the field entirely when
        // unchecked. Omitting the passphrase to the service means "on, generate one" - sending ""
        // is the operator deliberately turning it off. There is no third option in this build: no
        // free-text passphrase field, matching the mockup's plain checkbox.
        String passphrase = protect != null ? null : "";

        var body = new ExportController.GenerateRequest(purpose, reference, from, null, acknowledgeBlocked, passphrase);
        ResponseEntity<?> response = exportController.generate(id, body, principal);

        if (response.getStatusCode().is2xxSuccessful()) {
            Child child = childRepository.findDetailedById(id).orElseThrow();
            model.addAttribute("child", child);
            model.addAttribute("childIdentity", ChildIdentity.of(child, nameRevealService.isRevealed()));
            model.addAttribute("result", (ExportController.GenerateResponse) response.getBody());
            return "export/case-file-ready";
        }

        Child child = childRepository.findDetailedById(id).orElseThrow();
        model.addAttribute("child", child);
        model.addAttribute("childIdentity", ChildIdentity.of(child, nameRevealService.isRevealed()));
        model.addAttribute("manifest", exportController.manifest(id, from, null, principal));
        model.addAttribute("purposes", ExportPurpose.values());
        model.addAttribute("selectedPurpose", purpose);
        model.addAttribute("selectedReference", reference);

        if (response.getStatusCode() == HttpStatus.CONFLICT) {
            var blocked = (ExportController.BlockedResponse) response.getBody();
            model.addAttribute("blockedMessage", blocked.message());
            model.addAttribute("blockedEntries", blocked.blocked());
        } else {
            model.addAttribute("formError", ((ExportController.ErrorResponse) response.getBody()).message());
        }
        return "export/case-file-form";
    }

    private LocalDate periodFrom(String period) {
        return switch (period) {
            case "LAST_12_MONTHS" -> LocalDate.now().minusMonths(12);
            case "LAST_24_MONTHS" -> LocalDate.now().minusMonths(24);
            default -> null;
        };
    }
}
