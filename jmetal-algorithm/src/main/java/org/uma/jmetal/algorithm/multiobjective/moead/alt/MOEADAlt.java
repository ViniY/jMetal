package org.uma.jmetal.algorithm.multiobjective.moead.alt;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.uma.jmetal.algorithm.impl.AbstractDifferentialEvolution;
import org.uma.jmetal.algorithm.impl.AbstractEvolutionaryAlgorithm;
import org.uma.jmetal.algorithm.multiobjective.moead.util.MOEADUtils;
import org.uma.jmetal.operator.MutationOperator;
import org.uma.jmetal.operator.SelectionOperator;
import org.uma.jmetal.operator.impl.crossover.DifferentialEvolutionCrossover;
import org.uma.jmetal.operator.impl.mutation.PolynomialMutation;
import org.uma.jmetal.operator.impl.selection.DifferentialEvolutionSelection;
import org.uma.jmetal.operator.impl.selection.NaryRandomSelection;
import org.uma.jmetal.problem.DoubleProblem;
import org.uma.jmetal.solution.DoubleSolution;
import org.uma.jmetal.util.aggregativefunction.AggregativeFunction;
import org.uma.jmetal.util.aggregativefunction.impl.Tschebyscheff;
import org.uma.jmetal.util.evaluator.SolutionListEvaluator;
import org.uma.jmetal.util.evaluator.impl.SequentialSolutionListEvaluator;
import org.uma.jmetal.util.neighborhood.impl.WeightVectorNeighborhood;
import org.uma.jmetal.util.pseudorandom.JMetalRandom;

/**
 * Alternative implementation of MOEA/D. We have redesigned the code to allow MOEA/D to inherit from
 * the {@link AbstractDifferentialEvolution} class. The result is a more modular, reusable and
 * extensive code.
 */
public class MOEADAlt extends AbstractEvolutionaryAlgorithm<DoubleSolution, List<DoubleSolution>> {

  protected enum NeighborType {NEIGHBOR, POPULATION}

  private int evaluations;
  private int maxEvaluations;
  private int populationSize;
  private AggregativeFunction aggregativeFunction;

  private WeightVectorNeighborhood<DoubleSolution> weightVectorNeighborhood;

  private DifferentialEvolutionCrossover crossoverOperator;
  private SelectionOperator<List<DoubleSolution>, List<DoubleSolution>> selectionOperator;
  private MutationOperator<DoubleSolution> mutationOperator;

  private int neighborSize;
  private double neighborhoodSelectionProbability;
  private int maximumNumberOfReplacedSolutions;

  private int currentSubproblem;
  private NeighborType neighborType;

  private SolutionListEvaluator<DoubleSolution> evaluator;

  private Permutation permutation;

  public MOEADAlt(DoubleProblem problem, int populationSize, int maxEvaluations) {
    this(problem, populationSize, maxEvaluations, new Tschebyscheff());
  }

  public MOEADAlt(DoubleProblem problem, int populationSize, int maxEvaluations,
      AggregativeFunction aggregativeFunction) {
    this.problem = problem;
    this.populationSize = populationSize;
    this.maxEvaluations = maxEvaluations;
    this.aggregativeFunction = aggregativeFunction;

    neighborhoodSelectionProbability = 0.9;
    maximumNumberOfReplacedSolutions = 2;
    neighborSize = 20;

    crossoverOperator = new DifferentialEvolutionCrossover(1.0, 0.5, "rand/1/bin");
    selectionOperator = new NaryRandomSelection<>(2);
    mutationOperator = new PolynomialMutation(1.0 / problem.getNumberOfVariables(), 20.0);

    evaluator = new SequentialSolutionListEvaluator<>();

    weightVectorNeighborhood = new WeightVectorNeighborhood<DoubleSolution>(
        populationSize,
        neighborSize);

    permutation = new Permutation(populationSize);
  }

  @Override
  protected void initProgress() {
    evaluations += populationSize;
    for (DoubleSolution solution : population) {
      aggregativeFunction.update(solution.getObjectives());
    }
  }

  @Override
  protected void updateProgress() {
    evaluations++;
  }

  @Override
  protected boolean isStoppingConditionReached() {
    return evaluations >= maxEvaluations;
  }

  @Override
  protected List<DoubleSolution> createInitialPopulation() {
    List<DoubleSolution> population = new ArrayList<>();
    IntStream.range(0, populationSize)
        .forEach(i -> population.add(problem.createSolution()));

    return population;
  }

  @Override
  protected List<DoubleSolution> evaluatePopulation(List<DoubleSolution> population) {
    return evaluator.evaluate(population, getProblem());
  }

  @Override
  protected List<DoubleSolution> selection(List<DoubleSolution> population) {
    currentSubproblem = permutation.getNextElement();
    neighborType = chooseNeighborType();

    List<DoubleSolution> matingPool;
    if (neighborType.equals(NeighborType.NEIGHBOR)) {
      matingPool = selectionOperator
          .execute(weightVectorNeighborhood.getNeighbors(population, currentSubproblem));
    } else {
      matingPool = selectionOperator.execute(population);
    }
    
    matingPool.add(population.get(currentSubproblem));

    return matingPool;
  }

  @Override
  protected List<DoubleSolution> reproduction(List<DoubleSolution> matingPool) {
    crossoverOperator.setCurrentSolution(population.get(currentSubproblem));

    List<DoubleSolution> offspringPopulation = crossoverOperator.execute(matingPool);
    mutationOperator.execute(offspringPopulation.get(0));

    return offspringPopulation;
  }

  @Override
  protected List<DoubleSolution> replacement(List<DoubleSolution> population,
      List<DoubleSolution> offspringPopulation) {
    DoubleSolution newSolution = offspringPopulation.get(0);

    aggregativeFunction.update(newSolution.getObjectives());

    List<DoubleSolution> newPopulation;
    newPopulation = updateNeighborhood(
        newSolution, population, weightVectorNeighborhood.getNeighborhood()[currentSubproblem]);

    return newPopulation;
  }

  @Override
  public List<DoubleSolution> getResult() {
    return population;
  }

  @Override
  public String getName() {
    return "MOEA/D-DE";
  }

  @Override
  public String getDescription() {
    return "MOEA/D-DE";
  }

  protected NeighborType chooseNeighborType() {
    double rnd = JMetalRandom.getInstance().nextDouble();
    NeighborType neighborType;

    if (rnd < neighborhoodSelectionProbability) {
      neighborType = NeighborType.NEIGHBOR;
    } else {
      neighborType = NeighborType.POPULATION;
    }
    return neighborType;
  }

  protected List<DoubleSolution> updateNeighborhood(
      DoubleSolution newSolution,
      List<DoubleSolution> population,
      int[] neighborhood) {
    int size;
    if (neighborType == NeighborType.NEIGHBOR) {
      size = neighborhood.length;
    } else {
      size = populationSize;
    }

    int[] permutation = new int[size];
    MOEADUtils.randomPermutation(permutation, size);
    int count = 0;

    for (int i = 0; i < size; i++) {
      int k;
      if (neighborType == NeighborType.NEIGHBOR) {
        k = neighborhood[permutation[i]];
      } else {
        k = permutation[i];
      }

      double f1 = aggregativeFunction.compute(
          population.get(k).getObjectives(),
          weightVectorNeighborhood.getWeightVector()[k]);
      double f2 = aggregativeFunction.compute(
          newSolution.getObjectives(),
          weightVectorNeighborhood.getWeightVector()[k]);

      if (f2 < f1) {
        population.set(k, (DoubleSolution) newSolution.copy());
        count++;
      }

      if (count >= maximumNumberOfReplacedSolutions) {
        break;
      }
    }

    return population;
  }

  private static class Permutation {
    private int[] permutation;
    private int counter;

    public Permutation(int size) {
      permutation = new int[size];
      MOEADUtils.randomPermutation(permutation, size);
      counter = 0;
    }

    public int getNextElement() {
      int next = permutation[counter];
      counter++;
      if (counter == permutation.length) {
        MOEADUtils.randomPermutation(permutation, permutation.length);
        counter = 0;
      }

      return next;
    }
  }
}