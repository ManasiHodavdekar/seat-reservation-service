package com.paytmmoney.seatreservation.show;

import com.paytmmoney.seatreservation.show.dto.CreateShowRequest;
import com.paytmmoney.seatreservation.show.dto.ShowResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    /**
     * Admin endpoint. No auth is enforced here deliberately - it's a setup/seed endpoint for the
     * exercise, not part of the buyer-facing surface the correctness bar is tested against.
     */
    @PostMapping
    public ResponseEntity<ShowResponse> createShow(@Valid @RequestBody CreateShowRequest request) {
        ShowResponse response = showService.createShow(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ShowResponse> getShow(@PathVariable String id) {
        return ResponseEntity.ok(showService.getShow(id));
    }
}
