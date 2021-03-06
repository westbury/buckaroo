package com.loopperfect.buckaroo.sources;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.loopperfect.buckaroo.*;
import com.loopperfect.buckaroo.Process;
import com.loopperfect.buckaroo.tasks.CommonTasks;
import io.reactivex.Single;

import java.nio.file.Path;
import java.util.Objects;

public final class LazyCookbookRecipeSource implements RecipeSource {

    private final Path path;

    private LazyCookbookRecipeSource(final Path path) {
        Preconditions.checkNotNull(path);
        this.path = path;
    }

    public boolean equals(final LazyCookbookRecipeSource other) {
        Preconditions.checkNotNull(other);
        return Objects.equals(path, other.path);
    }

    @Override
    public boolean equals(final Object obj) {
        return this == obj ||
            obj != null &&
                obj instanceof LazyCookbookRecipeSource &&
                equals((LazyCookbookRecipeSource)obj);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    @Override
    public Process<Event, Recipe> fetch(final RecipeIdentifier identifier) {

        Preconditions.checkNotNull(identifier);

        final Single<Recipe> readRecipe = Single.fromCallable(() -> {
            if (identifier.source.isPresent()) {
                throw new IllegalArgumentException(identifier.encode() + " should be found on " + identifier.source.get());
            }
            return path.getFileSystem().getPath(
                path.toString(),
                "recipes",
                identifier.organization.name,
                identifier.recipe.name + ".json");
        }).flatMap(CommonTasks::readRecipeFile).onErrorResumeNext(error->
            Single.error(RecipeFetchException.wrap(this, identifier, error))
        );

        return Process.of(readRecipe);
    }

    @Override
    public Iterable<RecipeIdentifier> findCandidates(final PartialRecipeIdentifier partial) {
        try {
            return CommonTasks.readCookBook(path)
                .stream()
                .filter(x -> Objects.equals(x.recipe, partial.project))
                ::iterator; // TODO: implement sorting and scoring for organizations
        } catch (Throwable ignored) {
            return ImmutableList.of(); // TODO: return error
        }
    }

    @Override
    public Iterable<RecipeIdentifier> findSimilar(final RecipeIdentifier identifier) {
        try {
            final ImmutableList<RecipeIdentifier> candidates = CommonTasks.readCookBook(path);
            return Levenstein.findClosest(candidates, identifier);
        } catch (Throwable e) {
            return ImmutableList.of();
        }
    }

    public static RecipeSource of(final Path pathToCookbook) {
        return new LazyCookbookRecipeSource(pathToCookbook);
    }
}
