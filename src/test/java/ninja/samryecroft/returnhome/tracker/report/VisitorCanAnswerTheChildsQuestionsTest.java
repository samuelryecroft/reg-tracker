package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestTestFixtures;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.report.question.ReportQuestion;
import ninja.samryecroft.returnhome.tracker.report.question.ReportQuestions;
import ninja.samryecroft.returnhome.tracker.report.question.Respondent;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T244's mandatory boundary: <b>the hide must not reach the visitor's editable form.</b>
 *
 * <p>On a blank report {@code interviewAccepted} is null, and the read-only surfaces hide the nine
 * questions put to the young person. <b>If that hide applied to the capture form, the visitor could
 * not complete the section they are there to complete - and the null state would become unescapable
 * by construction:</b> the questions are hidden because the answer is unrecorded, and the answer
 * stays unrecorded because the form the visitor uses cannot show them.
 *
 * <p><b>Asserted against the rendered page, never against the expression.</b> The gate reads
 * {@code th:unless="${readonly and !childInterviewed}"} and that {@code readonly and} prefix looks
 * like it settles the question - but this project has spent a week on claims that were true of the
 * source and false of the screen. A truncated grep, a stale ref and a suite run against a stale base
 * all read as evidence. So this renders the form and looks.
 *
 * <p>The question set comes from the model rather than a list written out here: a new question put
 * to the child must be covered by this the day it is added, not the day someone remembers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VisitorCanAnswerTheChildsQuestionsTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private HomeRepository homeRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InterviewRequestRepository interviewRequestRepository;
    @Autowired private AppUserDetailsService appUserDetailsService;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void aVisitorOnABlankReportCanSeeAndAnswerAllNineOfTheChildsQuestions() throws Exception {
        String suffix = "-" + System.nanoTime();
        Home home = new Home();
        home.setName("Capture House" + suffix);
        home.setOrganisation(seededCareProvider());
        home = homeRepository.save(home);

        User visitor = new User();
        visitor.setUsername("capture-visitor" + suffix);
        visitor.setPassword(passwordEncoder.encode("password123"));
        visitor.setFirstName("Capture");
        visitor.setLastName("Visitor");
        visitor.setEmail("capture" + suffix + "@example.test");
        visitor.setRoles(new HashSet<>(Set.of(Role.VISITOR)));
        visitor.setHomes(new HashSet<>(Set.of(home)));
        visitor = userRepository.save(visitor);

        Child child = new Child();
        child.setFirstName("Capture");
        child.setLastName("Child");
        child.setDateOfBirth(LocalDate.of(2012, 5, 4));
        child.setHome(home);
        child = childRepository.save(child);

        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.SCHEDULED);
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(visitor);
        request.setAllocatedVisitor(visitor);
        request.setMissingSince(LocalDateTime.now().minusDays(3));
        request.setReturnedAt(LocalDateTime.now().minusHours(10));
        request.setScheduledAt(LocalDateTime.now().plusHours(2));
        Long requestId = interviewRequestRepository.save(request).getId();

        // No report row at all, so interviewAccepted is null - the state the read-only views hide on.
        String form = mockMvc.perform(get("/visitor/interviews/{id}/report", requestId)
                        .with(asUser(visitor.getUsername())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replace("&#39;", "'");

        List<ReportQuestion> childQuestions = ReportQuestions.ALL.stream()
                .filter(q -> q.answeredBy() == Respondent.CHILD)
                .toList();

        assertThat(childQuestions)
                .as("if this ever reaches zero the loop below asserts nothing at all")
                .hasSize(9);

        for (ReportQuestion question : childQuestions) {
            assertThat(form)
                    .as("the visitor is here to answer '%s' - hiding it because nobody has yet said "
                            + "whether the interview happened makes the null state unescapable",
                            question.id())
                    .contains(question.label());
            assertThat(form)
                    .as("and the control has to be there too, not just the words - a label with no "
                            + "input is a question that cannot be answered", question.id())
                    .contains("id=\"" + question.id() + "\"");
        }
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }
}
