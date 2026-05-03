package org.example;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.example.model.CounterResponse;
import org.example.model.CreateCounterRequest;
import org.example.model.MyVoteResponse;
import org.example.model.VoteRequest;

import java.util.List;
import java.util.Optional;

/**
 * Unchanged from challenge 4. The HTTP layer doesn't care that the underlying
 * storage moved from in-memory maps to SQLite — that's the whole point of keeping
 * it thin.
 */
@Path("/v1/counters")
@Produces(MediaType.APPLICATION_JSON)
public class CounterResource {

    private final CounterHelper helper;

    public CounterResource(CounterHelper helper) {
        this.helper = helper;
    }

    @GET
    public List<CounterResponse> list() {
        return helper.list().stream().map(CounterResponse::from).toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(CreateCounterRequest req) {
        if (req == null || req.getCounterId() == null || req.getCounterId().isEmpty()) {
            throw new BadRequestException("counterId is required");
        }
        if (!helper.create(req.getCounterId())) {
            throw new ClientErrorException("counter '" + req.getCounterId() + "' already exists",
                Response.Status.CONFLICT);
        }
        Counter c = helper.get(req.getCounterId()).orElseThrow();
        return Response.status(Response.Status.CREATED).entity(CounterResponse.from(c)).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        if (!helper.delete(id)) throw notFound(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/{id}")
    public CounterResponse get(@PathParam("id") String id) {
        Counter c = helper.get(id).orElseThrow(() -> notFound(id));
        return CounterResponse.from(c);
    }

    @PUT
    @Path("/{id}/vote")
    @Consumes(MediaType.APPLICATION_JSON)
    public CounterResponse vote(@PathParam("id") String id,
                                @HeaderParam("X-User-Id") String user,
                                VoteRequest req) {
        requireUser(user);
        if (req == null) throw new BadRequestException("request body is required");
        UserVote.Vote parsed;
        try {
            parsed = req.toVote();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        Counter c = helper.vote(user, id, parsed).orElseThrow(() -> notFound(id));
        return CounterResponse.from(c);
    }

    @DELETE
    @Path("/{id}/vote")
    public CounterResponse clearVote(@PathParam("id") String id,
                                     @HeaderParam("X-User-Id") String user) {
        requireUser(user);
        Counter c = helper.clearVote(user, id).orElseThrow(() -> notFound(id));
        return CounterResponse.from(c);
    }

    @GET
    @Path("/{id}/vote")
    public MyVoteResponse myVote(@PathParam("id") String id,
                                 @HeaderParam("X-User-Id") String user) {
        requireUser(user);
        Optional<Optional<UserVote.Vote>> result = helper.getMyVote(user, id);
        if (result.isEmpty()) throw notFound(id);
        return new MyVoteResponse(user, id, result.get().orElse(null));
    }

    private static void requireUser(String user) {
        if (user == null || user.isEmpty()) {
            throw new BadRequestException("X-User-Id header is required");
        }
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("no such counter '" + id + "'");
    }
}
